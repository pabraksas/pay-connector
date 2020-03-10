package uk.gov.pay.connector.charge.service;

import com.google.inject.persist.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.commons.model.SupportedLanguage;
import uk.gov.pay.commons.model.charge.ExternalMetadata;
import uk.gov.pay.connector.app.CaptureProcessConfig;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.app.LinksConfig;
import uk.gov.pay.connector.cardtype.dao.CardTypeDao;
import uk.gov.pay.connector.cardtype.model.domain.CardTypeEntity;
import uk.gov.pay.connector.charge.dao.ChargeDao;
import uk.gov.pay.connector.charge.exception.ChargeNotFoundRuntimeException;
import uk.gov.pay.connector.charge.exception.MotoPaymentNotAllowedForGatewayAccountException;
import uk.gov.pay.connector.charge.exception.ZeroAmountNotAllowedForGatewayAccountException;
import uk.gov.pay.connector.charge.model.AddressEntity;
import uk.gov.pay.connector.charge.model.CardDetailsEntity;
import uk.gov.pay.connector.charge.model.ChargeCreateRequest;
import uk.gov.pay.connector.charge.model.ChargeResponse;
import uk.gov.pay.connector.charge.model.FirstDigitsCardNumber;
import uk.gov.pay.connector.charge.model.LastDigitsCardNumber;
import uk.gov.pay.connector.charge.model.PrefilledCardHolderDetails;
import uk.gov.pay.connector.charge.model.ServicePaymentReference;
import uk.gov.pay.connector.charge.model.builder.AbstractChargeResponseBuilder;
import uk.gov.pay.connector.charge.model.domain.Auth3dsDetailsEntity;
import uk.gov.pay.connector.charge.model.domain.Charge;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.charge.model.domain.ParityCheckStatus;
import uk.gov.pay.connector.charge.model.domain.PersistedCard;
import uk.gov.pay.connector.charge.model.telephone.PaymentOutcome;
import uk.gov.pay.connector.charge.model.telephone.Supplemental;
import uk.gov.pay.connector.charge.model.telephone.TelephoneChargeCreateRequest;
import uk.gov.pay.connector.charge.resource.ChargesApiResource;
import uk.gov.pay.connector.charge.util.CorporateCardSurchargeCalculator;
import uk.gov.pay.connector.charge.util.RefundCalculator;
import uk.gov.pay.connector.chargeevent.dao.ChargeEventDao;
import uk.gov.pay.connector.chargeevent.model.domain.ChargeEventEntity;
import uk.gov.pay.connector.common.exception.ConflictRuntimeException;
import uk.gov.pay.connector.common.exception.IllegalStateRuntimeException;
import uk.gov.pay.connector.common.exception.InvalidForceStateTransitionException;
import uk.gov.pay.connector.common.exception.InvalidStateTransitionException;
import uk.gov.pay.connector.common.exception.OperationAlreadyInProgressRuntimeException;
import uk.gov.pay.connector.common.model.api.ExternalChargeState;
import uk.gov.pay.connector.common.model.api.ExternalTransactionState;
import uk.gov.pay.connector.common.model.domain.PaymentGatewayStateTransitions;
import uk.gov.pay.connector.common.model.domain.PrefilledAddress;
import uk.gov.pay.connector.common.service.PatchRequestBuilder;
import uk.gov.pay.connector.events.EventService;
import uk.gov.pay.connector.events.model.Event;
import uk.gov.pay.connector.events.model.charge.ExpungedChargeStatusCorrectedToCapturedToMatchGatewayStatus;
import uk.gov.pay.connector.events.model.charge.PaymentDetailsEntered;
import uk.gov.pay.connector.gateway.PaymentProviders;
import uk.gov.pay.connector.gateway.model.AuthCardDetails;
import uk.gov.pay.connector.gateway.model.PayersCardType;
import uk.gov.pay.connector.gatewayaccount.dao.GatewayAccountDao;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.paritycheck.LedgerService;
import uk.gov.pay.connector.paymentprocessor.model.OperationType;
import uk.gov.pay.connector.queue.QueueException;
import uk.gov.pay.connector.queue.StateTransitionService;
import uk.gov.pay.connector.refund.dao.RefundDao;
import uk.gov.pay.connector.refund.model.domain.RefundEntity;
import uk.gov.pay.connector.token.dao.TokenDao;
import uk.gov.pay.connector.token.model.domain.TokenEntity;
import uk.gov.pay.connector.wallets.WalletType;

import javax.inject.Inject;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static javax.ws.rs.HttpMethod.GET;
import static javax.ws.rs.HttpMethod.POST;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static uk.gov.pay.connector.charge.model.ChargeResponse.aChargeResponseBuilder;
import static uk.gov.pay.connector.charge.model.domain.ChargeEntity.TelephoneChargeEntityBuilder.aTelephoneChargeEntity;
import static uk.gov.pay.connector.charge.model.domain.ChargeEntity.WebChargeEntityBuilder.aWebChargeEntity;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_ERROR;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_REJECTED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AWAITING_CAPTURE_REQUEST;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURE_APPROVED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURE_SUBMITTED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CREATED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.ENTERING_CARD_DETAILS;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.PAYMENT_NOTIFICATION_CREATED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.fromString;
import static uk.gov.pay.connector.common.model.domain.NumbersInStringsSanitizer.sanitize;
import static uk.gov.pay.logging.LoggingKeys.GATEWAY_ACCOUNT_ID;
import static uk.gov.pay.logging.LoggingKeys.PAYMENT_EXTERNAL_ID;
import static uk.gov.pay.logging.LoggingKeys.PROVIDER;

public class ChargeService {
    private static final Logger logger = LoggerFactory.getLogger(ChargeService.class);

    private static final List<ChargeStatus> CURRENT_STATUSES_ALLOWING_UPDATE_TO_NEW_STATUS = newArrayList(CREATED, ENTERING_CARD_DETAILS);

    private final ChargeDao chargeDao;
    private final ChargeEventDao chargeEventDao;
    private final CardTypeDao cardTypeDao;
    private final TokenDao tokenDao;
    private final GatewayAccountDao gatewayAccountDao;
    private final LinksConfig linksConfig;
    private final CaptureProcessConfig captureProcessConfig;
    private final PaymentProviders providers;

    private final StateTransitionService stateTransitionService;
    private final LedgerService ledgerService;
    private final Boolean shouldEmitPaymentStateTransitionEvents;
    private final RefundDao refundDao;
    private EventService eventService;

    @Inject
    public ChargeService(TokenDao tokenDao, ChargeDao chargeDao, ChargeEventDao chargeEventDao,
                         CardTypeDao cardTypeDao, GatewayAccountDao gatewayAccountDao,
                         ConnectorConfiguration config, PaymentProviders providers,
                         StateTransitionService stateTransitionService, LedgerService ledgerService, EventService eventService,
                         RefundDao refundDao) {
        this.tokenDao = tokenDao;
        this.chargeDao = chargeDao;
        this.chargeEventDao = chargeEventDao;
        this.cardTypeDao = cardTypeDao;
        this.gatewayAccountDao = gatewayAccountDao;
        this.linksConfig = config.getLinks();
        this.providers = providers;
        this.captureProcessConfig = config.getCaptureProcessConfig();
        this.stateTransitionService = stateTransitionService;
        this.shouldEmitPaymentStateTransitionEvents = config.getEmitPaymentStateTransitionEvents();
        this.ledgerService = ledgerService;
        this.eventService = eventService;
        this.refundDao = refundDao;
    }

    @Transactional
    public Optional<ChargeResponse> findCharge(TelephoneChargeCreateRequest telephoneChargeRequest) {
        return chargeDao.findByGatewayTransactionId(telephoneChargeRequest.getProviderId())
                .map(charge -> populateResponseBuilderWith(aChargeResponseBuilder(), charge).build());
    }

    public Optional<ChargeResponse> create(TelephoneChargeCreateRequest telephoneChargeCreateRequest, Long accountId) {

        return createCharge(telephoneChargeCreateRequest, accountId)
                .map(charge ->
                        populateResponseBuilderWith(aChargeResponseBuilder(), charge).build());
    }

    @Transactional
    private Optional<ChargeEntity> createCharge(TelephoneChargeCreateRequest telephoneChargeRequest, Long accountId) {
        return gatewayAccountDao.findById(accountId).map(gatewayAccount -> {

            checkIfZeroAmountAllowed(telephoneChargeRequest.getAmount(), gatewayAccount);

            CardDetailsEntity cardDetails = new CardDetailsEntity(
                    LastDigitsCardNumber.ofNullable(telephoneChargeRequest.getLastFourDigits().orElse(null)),
                    FirstDigitsCardNumber.ofNullable(telephoneChargeRequest.getFirstSixDigits().orElse(null)),
                    telephoneChargeRequest.getNameOnCard().orElse(null),
                    telephoneChargeRequest.getCardExpiry().orElse(null),
                    telephoneChargeRequest.getCardType().orElse(null),
                    null
            );

            ChargeEntity chargeEntity = aTelephoneChargeEntity()
                    .withAmount(telephoneChargeRequest.getAmount())
                    .withDescription(telephoneChargeRequest.getDescription())
                    .withReference(ServicePaymentReference.of(telephoneChargeRequest.getReference()))
                    .withGatewayAccount(gatewayAccount)
                    .withEmail(telephoneChargeRequest.getEmailAddress().orElse(null))
                    .withExternalMetadata(storeExtraFieldsInMetaData(telephoneChargeRequest))
                    .withGatewayTransactionId(telephoneChargeRequest.getProviderId())
                    .withCardDetails(cardDetails)
                    .build();

            chargeDao.persist(chargeEntity);
            transitionChargeState(chargeEntity, PAYMENT_NOTIFICATION_CREATED);
            transitionChargeState(chargeEntity, internalChargeStatus(telephoneChargeRequest.getPaymentOutcome().getCode().orElse(null)));
            chargeDao.merge(chargeEntity);
            return chargeEntity;
        });
    }

    private ChargeStatus internalChargeStatus(String code) {
        if (code == null) {
            return CAPTURE_SUBMITTED;
        } else if ("P0010".equals(code)) {
            return AUTHORISATION_REJECTED;
        } else {
            return AUTHORISATION_ERROR;
        }
    }

    public Optional<ChargeResponse> create(ChargeCreateRequest chargeRequest, Long accountId, UriInfo uriInfo) {
        return createCharge(chargeRequest, accountId, uriInfo)
                .map(charge ->
                        populateResponseBuilderWith(aChargeResponseBuilder(), uriInfo, charge).build()
                );
    }

    @Transactional
    private Optional<ChargeEntity> createCharge(ChargeCreateRequest chargeRequest, Long accountId, UriInfo uriInfo) {
        return gatewayAccountDao.findById(accountId).map(gatewayAccount -> {

            checkIfZeroAmountAllowed(chargeRequest.getAmount(), gatewayAccount);
            checkIfMotoPaymentsAllowed(chargeRequest.isMoto(), gatewayAccount);

            if (gatewayAccount.isLive() && !chargeRequest.getReturnUrl().startsWith("https://")) {
                logger.info(String.format("Gateway account %d is LIVE, but is configured to use a non-https return_url", accountId));
            }

            SupportedLanguage language = chargeRequest.getLanguage() != null
                    ? chargeRequest.getLanguage()
                    : SupportedLanguage.ENGLISH;

            ChargeEntity chargeEntity = aWebChargeEntity()
                    .withAmount(chargeRequest.getAmount())
                    .withReturnUrl(chargeRequest.getReturnUrl())
                    .withDescription(chargeRequest.getDescription())
                    .withReference(ServicePaymentReference.of(chargeRequest.getReference()))
                    .withGatewayAccount(gatewayAccount)
                    .withEmail(chargeRequest.getEmail())
                    .withLanguage(language)
                    .withDelayedCapture(chargeRequest.isDelayedCapture())
                    .withExternalMetadata(chargeRequest.getExternalMetadata().orElse(null))
                    .withSource(chargeRequest.getSource())
                    .withMoto(chargeRequest.isMoto())
                    .build();

            chargeRequest.getPrefilledCardHolderDetails()
                    .map(this::createCardDetailsEntity)
                    .ifPresent(chargeEntity::setCardDetails);

            chargeDao.persist(chargeEntity);
            transitionChargeState(chargeEntity, CREATED);
            chargeDao.merge(chargeEntity);
            return chargeEntity;
        });
    }

    private CardDetailsEntity createCardDetailsEntity(PrefilledCardHolderDetails prefilledCardHolderDetails) {
        CardDetailsEntity cardDetailsEntity = new CardDetailsEntity();
        prefilledCardHolderDetails.getCardHolderName().ifPresent(cardDetailsEntity::setCardHolderName);
        prefilledCardHolderDetails.getAddress().map(PrefilledAddress::toAddress).map(AddressEntity::new).ifPresent(cardDetailsEntity::setBillingAddress);
        return cardDetailsEntity;
    }

    @Transactional
    public Optional<ChargeResponse> findChargeForAccount(String chargeId, Long accountId, UriInfo uriInfo) {
        return chargeDao
                .findByExternalIdAndGatewayAccount(chargeId, accountId)
                .map(chargeEntity -> populateResponseBuilderWith(aChargeResponseBuilder(), uriInfo, chargeEntity).build());
    }

    @Transactional
    public Optional<ChargeResponse> findChargeByGatewayTransactionId(String gatewayTransactionId, UriInfo uriInfo) {
        return chargeDao
                .findByGatewayTransactionId(gatewayTransactionId)
                .map(chargeEntity -> populateResponseBuilderWith(aChargeResponseBuilder(), uriInfo, chargeEntity).build());
    }

    @Transactional
    public Optional<ChargeEntity> updateChargeParityStatus(String externalId, ParityCheckStatus parityCheckStatus) {
        return chargeDao.findByExternalId(externalId)
                .map(chargeEntity -> {
                    chargeEntity.updateParityCheck(parityCheckStatus);
                    return Optional.of(chargeEntity);
                })
                .orElseGet(Optional::empty);
    }

    public Optional<Charge> findCharge(String chargeExternalId) {
        Optional<ChargeEntity> maybeChargeEntity = chargeDao.findByExternalId(chargeExternalId);

        if(maybeChargeEntity.isPresent()) {
            return maybeChargeEntity.map(Charge::from);
        }
        else{
            return ledgerService.getTransaction(chargeExternalId).map(Charge::from);
        }
    }

    public Optional<Charge> findCharge(String chargeExternalId, Long gatewayAccountId) {
        Optional<ChargeEntity> maybeChargeEntity = chargeDao.findByExternalIdAndGatewayAccount(chargeExternalId, gatewayAccountId);

        if(maybeChargeEntity.isPresent()) {
            return maybeChargeEntity.map(Charge::from);
        }
        else{
            return ledgerService.getTransactionForGatewayAccount(chargeExternalId, gatewayAccountId).map(Charge::from);
        }
    }

    public Optional<Charge> findByProviderAndTransactionIdFromDbOrLedger(String paymentGatewayName, String gatewayTransactionId) {
        return Optional.ofNullable(chargeDao.findByProviderAndTransactionId(paymentGatewayName, gatewayTransactionId)
                .map(Charge::from)
                .orElseGet(() -> findChargeFromLedger(paymentGatewayName, gatewayTransactionId).orElse(null)));
    }

    private Optional<Charge> findChargeFromLedger(String paymentGatewayName, String gatewayTransactionId) {
        return ledgerService.getTransactionForProviderAndGatewayTransactionId(paymentGatewayName,gatewayTransactionId).map(Charge::from);
    }

    @Transactional
    public Optional<ChargeEntity> updateCharge(String chargeId, PatchRequestBuilder.PatchRequest chargePatchRequest) {
        return chargeDao.findByExternalId(chargeId)
                .map(chargeEntity -> {
                    if (chargePatchRequest.getPath().equals(ChargesApiResource.EMAIL_KEY)) {
                        chargeEntity.setEmail(sanitize(chargePatchRequest.getValue()));
                    }
                    return Optional.of(chargeEntity);
                })
                .orElseGet(Optional::empty);
    }

    @Transactional
    public Optional<ChargeEntity> updateFromInitialStatus(String externalId, ChargeStatus newChargeStatus) {
        return chargeDao.findByExternalId(externalId)
                .map(chargeEntity -> {
                    final ChargeStatus oldChargeStatus = ChargeStatus.fromString(chargeEntity.getStatus());
                    if (CURRENT_STATUSES_ALLOWING_UPDATE_TO_NEW_STATUS.contains(oldChargeStatus)) {
                        transitionChargeState(chargeEntity, newChargeStatus);
                        return chargeEntity;
                    }
                    return null;
                });
    }

    private <T extends AbstractChargeResponseBuilder<T, R>, R> AbstractChargeResponseBuilder<T, R> populateResponseBuilderWith(AbstractChargeResponseBuilder<T, R> responseBuilder, ChargeEntity chargeEntity) {

        PersistedCard persistedCard = null;
        if (chargeEntity.getCardDetails() != null) {
            persistedCard = chargeEntity.getCardDetails().toCard();
        }

        T builderOfResponse = responseBuilder
                .withAmount(chargeEntity.getAmount())
                .withReference(chargeEntity.getReference())
                .withDescription(chargeEntity.getDescription())
                .withProviderId(chargeEntity.getGatewayTransactionId())
                .withCardDetails(persistedCard)
                .withEmail(chargeEntity.getEmail())
                .withChargeId(chargeEntity.getExternalId());

        chargeEntity.getExternalMetadata().ifPresent(externalMetadata -> {

            final PaymentOutcome paymentOutcome = new PaymentOutcome(
                    externalMetadata.getMetadata().get("status").toString()
            );

            ExternalTransactionState state;

            if (externalMetadata.getMetadata().get("status").toString().equals("success")) {
                state = new ExternalTransactionState(
                        externalMetadata.getMetadata().get("status").toString(),
                        true
                );
            } else {
                String message = Stream.of(ExternalChargeState.values())
                        .filter(chargeState -> chargeState.getCode() != null)
                        .collect(Collectors.toMap(ExternalChargeState::getCode, ExternalChargeState::getMessage))
                        .get(externalMetadata.getMetadata().get("code").toString());

                state = new ExternalTransactionState(
                        externalMetadata.getMetadata().get("status").toString(),
                        true,
                        externalMetadata.getMetadata().get("code").toString(),
                        message
                );
                paymentOutcome.setCode(externalMetadata.getMetadata().get("code").toString());
            }

            if (externalMetadata.getMetadata().get("error_code") != null || externalMetadata.getMetadata().get("error_message") != null) {
                paymentOutcome.setSupplemental(new Supplemental(
                        (String) externalMetadata.getMetadata().get("error_code"),
                        (String) externalMetadata.getMetadata().get("error_message")
                ));
            }

            if (externalMetadata.getMetadata().get("authorised_date") != null) {
                builderOfResponse.withAuthorisedDate(ZonedDateTime.parse(((String) externalMetadata.getMetadata().get("authorised_date"))));
            }

            if (externalMetadata.getMetadata().get("created_date") != null) {
                builderOfResponse.withCreatedDate(ZonedDateTime.parse(((String) externalMetadata.getMetadata().get("created_date"))));
            }

            builderOfResponse
                    .withProcessorId((String) externalMetadata.getMetadata().get("processor_id"))
                    .withAuthCode((String) externalMetadata.getMetadata().get("auth_code"))
                    .withTelephoneNumber((String) externalMetadata.getMetadata().get("telephone_number"))
                    .withState(state)
                    .withPaymentOutcome(paymentOutcome);
        });

        return builderOfResponse;
    }

    public <T extends AbstractChargeResponseBuilder<T, R>, R> AbstractChargeResponseBuilder<T, R> populateResponseBuilderWith(AbstractChargeResponseBuilder<T, R> responseBuilder, UriInfo uriInfo, ChargeEntity chargeEntity) {
        String chargeId = chargeEntity.getExternalId();
        PersistedCard persistedCard = null;
        if (chargeEntity.getCardDetails() != null) {
            persistedCard = chargeEntity.getCardDetails().toCard();
            persistedCard.setCardBrand(findCardBrandLabel(chargeEntity.getCardDetails().getCardBrand()).orElse(""));
        }

        ChargeResponse.Auth3dsData auth3dsData = null;
        if (chargeEntity.get3dsDetails() != null) {
            auth3dsData = new ChargeResponse.Auth3dsData();
            auth3dsData.setPaRequest(chargeEntity.get3dsDetails().getPaRequest());
            auth3dsData.setIssuerUrl(chargeEntity.get3dsDetails().getIssuerUrl());
        }
        ExternalChargeState externalChargeState = ChargeStatus.fromString(chargeEntity.getStatus()).toExternal();

        T builderOfResponse = responseBuilder
                .withChargeId(chargeId)
                .withAmount(chargeEntity.getAmount())
                .withReference(chargeEntity.getReference())
                .withDescription(chargeEntity.getDescription())
                .withState(new ExternalTransactionState(externalChargeState.getStatus(), externalChargeState.isFinished(), externalChargeState.getCode(), externalChargeState.getMessage()))
                .withGatewayTransactionId(chargeEntity.getGatewayTransactionId())
                .withProviderName(chargeEntity.getGatewayAccount().getGatewayName())
                .withCreatedDate(chargeEntity.getCreatedDate())
                .withReturnUrl(chargeEntity.getReturnUrl())
                .withEmail(chargeEntity.getEmail())
                .withLanguage(chargeEntity.getLanguage())
                .withDelayedCapture(chargeEntity.isDelayedCapture())
                .withRefunds(buildRefundSummary(chargeEntity))
                .withSettlement(buildSettlementSummary(chargeEntity))
                .withCardDetails(persistedCard)
                .withAuth3dsData(auth3dsData)
                .withLink("self", GET, selfUriFor(uriInfo, chargeEntity.getGatewayAccount().getId(), chargeId))
                .withLink("refunds", GET, refundsUriFor(uriInfo, chargeEntity.getGatewayAccount().getId(), chargeEntity.getExternalId()))
                .withWalletType(chargeEntity.getWalletType())
                .withMoto(chargeEntity.isMoto());

        chargeEntity.getFeeAmount().ifPresent(builderOfResponse::withFee);
        chargeEntity.getExternalMetadata().ifPresent(builderOfResponse::withExternalMetadata);

        if (ChargeStatus.AWAITING_CAPTURE_REQUEST.getValue().equals(chargeEntity.getStatus())) {
            builderOfResponse.withLink("capture", POST, captureUriFor(uriInfo, chargeEntity.getGatewayAccount().getId(), chargeEntity.getExternalId()));
        }

        chargeEntity.getCorporateSurcharge().ifPresent(corporateSurcharge ->
                builderOfResponse.withCorporateCardSurcharge(corporateSurcharge)
                        .withTotalAmount(CorporateCardSurchargeCalculator.getTotalAmountFor(chargeEntity)));

        // @TODO(sfount) consider if total and net columns could be calculation columns in postgres (single source of truth)
        chargeEntity.getNetAmount().ifPresent(builderOfResponse::withNetAmount);

        if (needsNextUrl(chargeEntity)) {
            TokenEntity token = createNewChargeEntityToken(chargeEntity);
            Map<String, Object> params = new HashMap<>();
            params.put("chargeTokenId", token.getToken());

            return builderOfResponse
                    .withLink("next_url", GET, nextUrl(token.getToken()))
                    .withLink("next_url_post", POST, nextUrl(), APPLICATION_FORM_URLENCODED, params);
        } else {
            return builderOfResponse;
        }
    }

    private boolean needsNextUrl(ChargeEntity chargeEntity) {
        ChargeStatus chargeStatus = ChargeStatus.fromString(chargeEntity.getStatus());
        return !chargeStatus.toExternal().isFinished() && !chargeStatus.equals(AWAITING_CAPTURE_REQUEST);
    }

    public ChargeEntity updateChargePostCardAuthorisation(String chargeExternalId,
                                                          ChargeStatus status,
                                                          Optional<String> transactionId,
                                                          Optional<Auth3dsDetailsEntity> auth3dsDetails,
                                                          Optional<String> sessionIdentifier,
                                                          AuthCardDetails authCardDetails) {
        return updateChargeAndEmitEventPostAuthorisation(chargeExternalId, status, authCardDetails, transactionId, auth3dsDetails, sessionIdentifier,
                Optional.empty(), Optional.empty());

    }

    public ChargeEntity updateChargePostWalletAuthorisation(String chargeExternalId,
                                                            ChargeStatus status,
                                                            Optional<String> transactionId,
                                                            Optional<String> sessionIdentifier,
                                                            AuthCardDetails authCardDetails,
                                                            WalletType walletType,
                                                            String emailAddress) {
        return updateChargeAndEmitEventPostAuthorisation(chargeExternalId, status, authCardDetails, transactionId, Optional.empty(), sessionIdentifier,
                ofNullable(walletType), ofNullable(emailAddress));
    }

    public ChargeEntity updateChargeAndEmitEventPostAuthorisation(String chargeExternalId,
                                                                  ChargeStatus status,
                                                                  AuthCardDetails authCardDetails,
                                                                  Optional<String> transactionId,
                                                                  Optional<Auth3dsDetailsEntity> auth3dsDetails,
                                                                  Optional<String> sessionIdentifier,
                                                                  Optional<WalletType> walletType,
                                                                  Optional<String> emailAddress) {
        updateChargePostAuthorisation(chargeExternalId, status, authCardDetails, transactionId,
                auth3dsDetails, sessionIdentifier, walletType, emailAddress);
        ChargeEntity chargeEntity = findChargeByExternalId(chargeExternalId);

        eventService.emitAndRecordEvent(PaymentDetailsEntered.from(chargeEntity));

        return chargeEntity;
    }

    // cannot be private: Guice requires @Transactional methods to be public
    @Transactional
    public ChargeEntity updateChargePostAuthorisation(String chargeExternalId,
                                                      ChargeStatus status,
                                                      AuthCardDetails authCardDetails,
                                                      Optional<String> transactionId,
                                                      Optional<Auth3dsDetailsEntity> auth3dsDetails,
                                                      Optional<String> sessionIdentifier,
                                                      Optional<WalletType> walletType,
                                                      Optional<String> emailAddress) {
        return chargeDao.findByExternalId(chargeExternalId).map(charge -> {
            setTransactionId(charge, transactionId);
            sessionIdentifier.ifPresent(charge::setProviderSessionId);
            auth3dsDetails.ifPresent(charge::set3dsDetails);
            walletType.ifPresent(charge::setWalletType);
            emailAddress.ifPresent(charge::setEmail);

            CardDetailsEntity detailsEntity = buildCardDetailsEntity(authCardDetails);
            charge.setCardDetails(detailsEntity);

            transitionChargeState(charge, status);

            logger.info("Stored confirmation details for charge - charge_external_id={}",
                    chargeExternalId);

            return charge;
        }).orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeExternalId));
    }

    @Transactional
    public ChargeEntity updateChargePost3dsAuthorisation(String chargeExternalId, ChargeStatus status,
                                                         OperationType operationType,
                                                         Optional<String> transactionId) {
        return chargeDao.findByExternalId(chargeExternalId).map(charge -> {
            try {
                setTransactionId(charge, transactionId);
                transitionChargeState(charge, status);
            } catch (InvalidStateTransitionException e) {
                if (chargeIsInLockedStatus(operationType, charge)) {
                    throw new OperationAlreadyInProgressRuntimeException(operationType.getValue(), charge.getExternalId());
                }
                throw new IllegalStateRuntimeException(charge.getExternalId());
            }
            return charge;
        }).orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeExternalId));
    }

    public ChargeEntity updateChargePostCapture(String chargeId, ChargeStatus nextStatus) {
        return chargeDao.findByExternalId(chargeId)
                .map(chargeEntity -> {
                    if (nextStatus == CAPTURED) {
                        transitionChargeState(chargeEntity, CAPTURE_SUBMITTED);
                        transitionChargeState(chargeEntity, CAPTURED);
                    } else {
                        transitionChargeState(chargeEntity, nextStatus);
                    }
                    return chargeEntity;
                })
                .orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeId));
    }

    private void setTransactionId(ChargeEntity chargeEntity, Optional<String> transactionId) {
        transactionId.ifPresent(txId -> {
            if (!isBlank(txId)) {
                chargeEntity.setGatewayTransactionId(txId);
            }
        });
    }

    @Transactional
    public ChargeEntity lockChargeForProcessing(String chargeId, OperationType operationType) {
        return chargeDao.findByExternalId(chargeId).map(chargeEntity -> {
            try {

                GatewayAccountEntity gatewayAccount = chargeEntity.getGatewayAccount();

                // Used by Splunk saved search
                logger.info("Card pre-operation - charge_external_id={}, charge_status={}, account_id={}, amount={}, operation_type={}, provider={}, provider_type={}, locking_status={}",
                        chargeEntity.getExternalId(),
                        fromString(chargeEntity.getStatus()),
                        gatewayAccount.getId(),
                        chargeEntity.getAmount(),
                        operationType.getValue(),
                        gatewayAccount.getGatewayName(),
                        gatewayAccount.getType(),
                        operationType.getLockingStatus());

                chargeEntity.setStatus(operationType.getLockingStatus());

            } catch (InvalidStateTransitionException e) {
                if (chargeIsInLockedStatus(operationType, chargeEntity)) {
                    throw new OperationAlreadyInProgressRuntimeException(operationType.getValue(), chargeEntity.getExternalId());
                }
                throw new IllegalStateRuntimeException(chargeEntity.getExternalId());
            }
            return chargeEntity;
        }).orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeId));
    }

    public int getNumberOfChargesAwaitingCapture(Duration notAttemptedWithin) {
        return chargeDao.countChargesForImmediateCapture(notAttemptedWithin);
    }

    public ChargeEntity findChargeByExternalId(String chargeId) {
        return chargeDao.findByExternalId(chargeId)
                .orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeId));
    }

    public ChargeEntity transitionChargeState(ChargeEntity charge, ChargeStatus targetChargeState) {
        return transitionChargeState(charge, targetChargeState, null);
    }

    @Transactional
    public ChargeEntity transitionChargeState(
            ChargeEntity charge,
            ChargeStatus targetChargeState,
            ZonedDateTime gatewayEventTime
    ) {
        ChargeStatus fromChargeState = ChargeStatus.fromString(charge.getStatus());
        charge.setStatus(targetChargeState);
        ChargeEventEntity chargeEventEntity = chargeEventDao.persistChargeEventOf(charge, gatewayEventTime);

        if (shouldEmitPaymentStateTransitionEvents) {
            stateTransitionService.offerPaymentStateTransition(charge.getExternalId(), fromChargeState, targetChargeState, chargeEventEntity);
        }

        return charge;
    }

    @Transactional
    public ChargeEntity transitionChargeState(String chargeId, ChargeStatus targetChargeState) {
        return chargeDao.findByExternalId(chargeId).map(chargeEntity ->
                transitionChargeState(chargeEntity, targetChargeState)
        ).orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeId));
    }
    
    @Transactional
    public <T extends Event> ChargeEntity forceTransitionChargeState(ChargeEntity charge, ChargeStatus targetChargeState) {
        ChargeStatus fromChargeState = ChargeStatus.fromString(charge.getStatus());

        return PaymentGatewayStateTransitions.getEventForForceUpdate(targetChargeState).map(eventClass -> {
            logger.info(format("Force state transition from [%s] to [%s]", charge.getStatus(), targetChargeState.getValue()), 
                    List.of(kv(PAYMENT_EXTERNAL_ID, charge.getExternalId()),
                            kv(GATEWAY_ACCOUNT_ID, charge.getGatewayAccount().getId()),
                            kv(PROVIDER, charge.getGatewayAccount().getGatewayName())));
            
            charge.setStatusIgnoringValidTransitions(targetChargeState);
            chargeDao.merge(charge);
            ChargeEventEntity chargeEventEntity = chargeEventDao.persistChargeEventOf(charge);

            if (shouldEmitPaymentStateTransitionEvents) {
                stateTransitionService.offerPaymentStateTransition(
                        charge.getExternalId(), fromChargeState, targetChargeState, chargeEventEntity,
                        eventClass);
            }
            
            return charge;
        }).orElseThrow(() -> new InvalidForceStateTransitionException(fromChargeState, targetChargeState));
    }

    public Optional<ChargeEntity> findByProviderAndTransactionId(String paymentGatewayName, String transactionId) {
        return chargeDao.findByProviderAndTransactionId(paymentGatewayName, transactionId);
    }
    
    public void forceCaptureForExpungedCharge(Charge charge, GatewayAccountEntity gatewayAccountEntity, ChargeStatus targetChargeState, ZonedDateTime gatewayEventDate) {
        Event event = new ExpungedChargeStatusCorrectedToCapturedToMatchGatewayStatus(charge.getExternalId(), gatewayEventDate);

        logger.info(format("Force state transition from [%s] to [%s]", charge.getStatus(), targetChargeState.getValue()),
                List.of(kv(PAYMENT_EXTERNAL_ID, charge.getExternalId()),
                        kv(GATEWAY_ACCOUNT_ID, gatewayAccountEntity.getId()),
                        kv(PROVIDER, gatewayAccountEntity.getGatewayName())));
        
        try {
            eventService.emitEvent(event);
        } catch (QueueException e) {
            logger.error("Failed to emit event {} due to {} [externalId={}]", event.getEventType(), e.getMessage(), event.getResourceExternalId());
        }
    }

    @Transactional
    public ChargeEntity markChargeAsEligibleForCapture(String externalId) {
        return chargeDao.findByExternalId(externalId).map(charge -> {
            ChargeStatus targetStatus = charge.isDelayedCapture() ? AWAITING_CAPTURE_REQUEST : CAPTURE_APPROVED;

            try {
                transitionChargeState(charge, targetStatus);
            } catch (InvalidStateTransitionException e) {
                throw new IllegalStateRuntimeException(charge.getExternalId());
            }

            return charge;
        }).orElseThrow(() -> new ChargeNotFoundRuntimeException(externalId));
    }

    @Transactional
    public ChargeEntity markDelayedCaptureChargeAsCaptureApproved(String externalId) {
        return chargeDao.findByExternalId(externalId).map(charge -> {
            switch (fromString(charge.getStatus())) {
                case AWAITING_CAPTURE_REQUEST:
                    try {
                        transitionChargeState(charge, CAPTURE_APPROVED);
                    } catch (InvalidStateTransitionException e) {
                        throw new ConflictRuntimeException(charge.getExternalId(),
                                "attempt to perform delayed capture on invalid charge state " + e.getMessage());
                    }

                    return charge;

                case CAPTURE_APPROVED:
                case CAPTURE_APPROVED_RETRY:
                case CAPTURE_READY:
                case CAPTURE_SUBMITTED:
                case CAPTURED:
                    return charge;

                default:
                    throw new ConflictRuntimeException(charge.getExternalId(),
                            format("attempt to perform delayed capture on charge not in %s state.", AWAITING_CAPTURE_REQUEST)
                    );
            }

        }).orElseThrow(() -> new ChargeNotFoundRuntimeException(externalId));
    }

    public boolean isChargeRetriable(String externalId) {
        int numberOfChargeRetries = chargeDao.countCaptureRetriesForChargeExternalId(externalId);
        return numberOfChargeRetries <= captureProcessConfig.getMaximumRetries();
    }

    public boolean isChargeCaptureSuccess(String externalId) {
        ChargeEntity charge = findChargeByExternalId(externalId);
        ChargeStatus status = ChargeStatus.fromString(charge.getStatus());
        return status == CAPTURED || status == CAPTURE_SUBMITTED;
    }

    private CardDetailsEntity buildCardDetailsEntity(AuthCardDetails authCardDetails) {
        CardDetailsEntity detailsEntity = new CardDetailsEntity();
        detailsEntity.setCardBrand(sanitize(authCardDetails.getCardBrand()));
        detailsEntity.setCardHolderName(sanitize(authCardDetails.getCardHolder()));
        detailsEntity.setExpiryDate(authCardDetails.getEndDate());
        if (hasFullCardNumber(authCardDetails)) { // Apple Pay etc. don’t give us a full card number, just the last four digits here
            detailsEntity.setFirstDigitsCardNumber(FirstDigitsCardNumber.of(StringUtils.left(authCardDetails.getCardNo(), 6)));
        }
        detailsEntity.setLastDigitsCardNumber(LastDigitsCardNumber.of(StringUtils.right(authCardDetails.getCardNo(), 4)));

        if (authCardDetails.getAddress().isPresent())
            detailsEntity.setBillingAddress(new AddressEntity(authCardDetails.getAddress().get()));

        detailsEntity.setCardType(PayersCardType.toCardType(authCardDetails.getPayersCardType()));

        return detailsEntity;
    }

    private boolean hasFullCardNumber(AuthCardDetails authCardDetails) {
        return authCardDetails.getCardNo().length() > 6;
    }

    private TokenEntity createNewChargeEntityToken(ChargeEntity chargeEntity) {
        TokenEntity token = TokenEntity.generateNewTokenFor(chargeEntity);
        tokenDao.persist(token);
        return token;
    }

    private Optional<String> findCardBrandLabel(String cardBrand) {
        if (cardBrand == null) {
            return Optional.empty();
        }

        return cardTypeDao.findByBrand(cardBrand)
                .stream()
                .findFirst()
                .map(CardTypeEntity::getLabel);
    }

    private ChargeResponse.RefundSummary buildRefundSummary(ChargeEntity chargeEntity) {
        ChargeResponse.RefundSummary refund = new ChargeResponse.RefundSummary();
        Charge charge = Charge.from(chargeEntity);
        List<RefundEntity> refundEntityList = refundDao.findRefundsByChargeExternalId(chargeEntity.getExternalId());
        refund.setStatus(providers.byName(chargeEntity.getPaymentGatewayName()).getExternalChargeRefundAvailability(charge, refundEntityList).getStatus());
        refund.setAmountSubmitted(RefundCalculator.getRefundedAmount(refundEntityList));
        refund.setAmountAvailable(RefundCalculator.getTotalAmountAvailableToBeRefunded(charge, refundEntityList));
        return refund;
    }

    private ChargeResponse.SettlementSummary buildSettlementSummary(ChargeEntity charge) {
        ChargeResponse.SettlementSummary settlement = new ChargeResponse.SettlementSummary();

        settlement.setCaptureSubmitTime(charge.getCaptureSubmitTime());
        settlement.setCapturedTime(charge.getCapturedTime());

        return settlement;
    }

    private URI selfUriFor(UriInfo uriInfo, Long accountId, String chargeId) {
        return uriInfo.getBaseUriBuilder()
                .path("/v1/api/accounts/{accountId}/charges/{chargeId}")
                .build(accountId, chargeId);
    }

    private URI refundsUriFor(UriInfo uriInfo, Long accountId, String chargeId) {
        return uriInfo.getBaseUriBuilder()
                .path("/v1/api/accounts/{accountId}/charges/{chargeId}/refunds")
                .build(accountId, chargeId);
    }

    private URI captureUriFor(UriInfo uriInfo, Long accountId, String chargeId) {
        return uriInfo.getBaseUriBuilder()
                .path("/v1/api/accounts/{accountId}/charges/{chargeId}/capture")
                .build(accountId, chargeId);
    }

    private URI nextUrl(String tokenId) {
        return UriBuilder.fromUri(linksConfig.getFrontendUrl())
                .path("secure")
                .path(tokenId)
                .build();
    }

    private URI nextUrl() {
        return UriBuilder.fromUri(linksConfig.getFrontendUrl())
                .path("secure")
                .build();
    }

    private boolean chargeIsInLockedStatus(OperationType operationType, ChargeEntity chargeEntity) {
        return operationType.getLockingStatus().equals(ChargeStatus.fromString(chargeEntity.getStatus()));
    }

    private ExternalMetadata storeExtraFieldsInMetaData(TelephoneChargeCreateRequest telephoneChargeRequest) {
        HashMap<String, Object> telephoneJSON = new HashMap<>();
        String processorId = telephoneChargeRequest.getProcessorId();
        telephoneJSON.put("processor_id", checkAndGetTruncatedValue(processorId, "processor_id", processorId));
        telephoneJSON.put("status", telephoneChargeRequest.getPaymentOutcome().getStatus());
        telephoneChargeRequest.getCreatedDate().ifPresent(createdDate -> telephoneJSON.put("created_date", createdDate));
        telephoneChargeRequest.getAuthorisedDate().ifPresent(authorisedDate -> telephoneJSON.put("authorised_date", authorisedDate));
        telephoneChargeRequest.getAuthCode().ifPresent(authCode -> telephoneJSON.put("auth_code", checkAndGetTruncatedValue(processorId, "auth_code", authCode)));
        telephoneChargeRequest.getTelephoneNumber().ifPresent(telephoneNumber -> telephoneJSON.put("telephone_number", checkAndGetTruncatedValue(processorId, "telephone_number", telephoneNumber)));
        telephoneChargeRequest.getPaymentOutcome().getCode().ifPresent(code -> telephoneJSON.put("code", code));
        telephoneChargeRequest.getPaymentOutcome().getSupplemental().ifPresent(
                supplemental -> {
                    supplemental.getErrorCode().ifPresent(errorCode -> telephoneJSON.put("error_code", checkAndGetTruncatedValue(processorId, "error_code", errorCode)));
                    supplemental.getErrorMessage().ifPresent(errorMessage -> telephoneJSON.put("error_message", checkAndGetTruncatedValue(processorId, "error_message", errorMessage)));
                }
        );

        return new ExternalMetadata(telephoneJSON);
    }

    private String checkAndGetTruncatedValue(String processorId, String field, String value) {
        if (value.length() > 50) {
            logger.info("Telephone payment {} - {} field is longer than 50 characters and has been truncated and stored. Actual value is {}", processorId, field, value);
            return value.substring(0, 50);
        }
        return value;
    }

    private void checkIfZeroAmountAllowed(Long amount, GatewayAccountEntity gatewayAccount) {
        if (amount == 0L && !gatewayAccount.isAllowZeroAmount()) {
            throw new ZeroAmountNotAllowedForGatewayAccountException(gatewayAccount.getId());
        }
    }

    private void checkIfMotoPaymentsAllowed(boolean moto, GatewayAccountEntity gatewayAccount) {
        if (moto && !gatewayAccount.isAllowMoto()) {
            throw new MotoPaymentNotAllowedForGatewayAccountException(gatewayAccount.getId());
        }
    }
}
