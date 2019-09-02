package uk.gov.pay.connector.dao;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Test;
import uk.gov.pay.connector.charge.dao.ChargeDao;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.chargeevent.dao.ChargeEventDao;
import uk.gov.pay.connector.chargeevent.model.domain.ChargeEventEntity;
import uk.gov.pay.connector.it.dao.DaoITestBase;
import uk.gov.pay.connector.it.dao.DatabaseFixtures;
import uk.gov.pay.connector.util.RandomIdGenerator;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static java.time.ZonedDateTime.now;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang.math.RandomUtils.nextLong;
import static org.exparity.hamcrest.date.ZonedDateTimeMatchers.within;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_READY;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_SUCCESS;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AWAITING_CAPTURE_REQUEST;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURE_APPROVED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURE_READY;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.ENTERING_CARD_DETAILS;
import static uk.gov.pay.connector.it.dao.DatabaseFixtures.withDatabaseTestHelper;

public class ChargeEventDaoIT extends DaoITestBase {

    private static final String TRANSACTION_ID = "345654";
    private static final String TRANSACTION_ID_2 = "345655";
    private static final String TRANSACTION_ID_3 = "345656";

    private ChargeDao chargeDao;
    private ChargeEventDao chargeEventDao;
    private DatabaseFixtures.TestAccount defaultTestAccount;

    @Before
    public void setUp() {
        chargeDao = env.getInstance(ChargeDao.class);
        chargeEventDao = env.getInstance(ChargeEventDao.class);
        this.defaultTestAccount = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestAccount()
                .insert();
    }

    @Test
    public void persistChargeEventOfChargeEntity_succeeds() {

        Long chargeId = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestCharge()
                .withTestAccount(defaultTestAccount)
                .withChargeId(nextLong())
                .withExternalChargeId(RandomIdGenerator.newId())
                .withTransactionId(TRANSACTION_ID)
                .insert()
                .getChargeId();

        ChargeEntity entity = chargeDao.findById(chargeId).get();
        entity.setStatus(ENTERING_CARD_DETAILS);

        chargeEventDao.persistChargeEventOf(entity);

        //move status to AUTHORISED
        entity.setStatus(AUTHORISATION_READY);
        entity.setStatus(AUTHORISATION_SUCCESS);
        entity.setGatewayTransactionId(TRANSACTION_ID_2);

        chargeEventDao.persistChargeEventOf(entity);

        entity.setStatus(CAPTURE_READY);
        entity.setGatewayTransactionId(TRANSACTION_ID_3);

        chargeEventDao.persistChargeEventOf(entity);

        List<ChargeEventEntity> events = chargeDao.findById(chargeId).get().getEvents();

        assertThat(events, hasSize(3));
        assertThat(events, shouldIncludeStatus(ENTERING_CARD_DETAILS));
        assertThat(events, shouldIncludeStatus(AUTHORISATION_SUCCESS));
        assertThat(events, shouldIncludeStatus(CAPTURE_READY));
        assertDateMatch(events.get(0).getUpdated());
    }

    @Test
    public void shouldPersistEventForStatus_awaitingCaptureRequest() {

        Long chargeId = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestCharge()
                .withTestAccount(defaultTestAccount)
                .withChargeId(nextLong())
                .withExternalChargeId(RandomIdGenerator.newId())
                .withTransactionId(TRANSACTION_ID)
                .withDelayedCapture(true)
                .insert()
                .getChargeId();

        ChargeEntity entity = chargeDao.findById(chargeId).get();
        entity.setStatus(ENTERING_CARD_DETAILS);

        chargeEventDao.persistChargeEventOf(entity);

        //move status to AUTHORISED
        entity.setStatus(AUTHORISATION_READY);
        entity.setStatus(AUTHORISATION_SUCCESS);
        entity.setGatewayTransactionId(TRANSACTION_ID_2);

        chargeEventDao.persistChargeEventOf(entity);

        entity.setStatus(AWAITING_CAPTURE_REQUEST);
        entity.setGatewayTransactionId(TRANSACTION_ID_3);

        chargeEventDao.persistChargeEventOf(entity);

        List<ChargeEventEntity> events = chargeDao.findById(chargeId).get().getEvents();

        assertThat(events, hasSize(3));
        assertThat(events, shouldIncludeStatus(ENTERING_CARD_DETAILS));
        assertThat(events, shouldIncludeStatus(AUTHORISATION_SUCCESS));
        assertThat(events, shouldIncludeStatus(AWAITING_CAPTURE_REQUEST));
        assertDateMatch(events.get(0).getUpdated());
    }

    @Test
    public void findChargeEventsByDateRangeShouldReturnResultCorrectly() {
        ZonedDateTime eventDate = ZonedDateTime.parse("2016-01-01T00:00:00Z");

        DatabaseFixtures.TestCharge testCharge = createTestCharge();

        createTestChargeEvent(testCharge, ENTERING_CARD_DETAILS, eventDate.minusHours(10));
        createTestChargeEvent(testCharge, AUTHORISATION_READY, eventDate);
        createTestChargeEvent(testCharge, AUTHORISATION_SUCCESS, eventDate.plusMinutes(1));
        createTestChargeEvent(testCharge, CAPTURE_APPROVED, eventDate.plusHours(10));

        List<ChargeEventEntity> events = chargeEventDao.findChargeEvents(eventDate, eventDate.plusMinutes(1), 1, 2);

        assertThat(events, hasSize(2));
        assertThat(events, shouldIncludeStatus(AUTHORISATION_READY));
        assertThat(events.get(0).getChargeEntity().getId(), Matchers.is(testCharge.getChargeId()));
        assertThat(events, shouldIncludeStatus(AUTHORISATION_SUCCESS));
        assertThat(events.get(1).getChargeEntity().getId(), Matchers.is(testCharge.getChargeId()));
    }

    private DatabaseFixtures.TestCharge createTestCharge() {
        DatabaseFixtures.TestAccount testAccount = withDatabaseTestHelper(databaseTestHelper)
                .aTestAccount().insert();
        return withDatabaseTestHelper(databaseTestHelper)
                .aTestCharge()
                .withTestAccount(testAccount)
                .insert();
    }

    private void createTestChargeEvent(DatabaseFixtures.TestCharge testCharge,
                                       ChargeStatus status, ZonedDateTime eventDate) {
        withDatabaseTestHelper(databaseTestHelper)
                .aTestChargeEvent()
                .withChargeStatus(status)
                .withDate(eventDate)
                .withTestCharge(testCharge)
                .insert();
    }

    private void assertDateMatch(ZonedDateTime createdDateTime) {
        assertThat(createdDateTime, within(1, ChronoUnit.MINUTES, now()));
    }

    private Matcher<? super List<ChargeEventEntity>> shouldIncludeStatus(ChargeStatus... expectedStatuses) {
        return new TypeSafeMatcher<List<ChargeEventEntity>>() {
            @Override
            protected boolean matchesSafely(List<ChargeEventEntity> chargeEvents) {
                List<ChargeStatus> actualStatuses = chargeEvents.stream()
                        .map(ChargeEventEntity::getStatus)
                        .collect(toList());
                return actualStatuses.containsAll(asList(expectedStatuses));
            }

            @Override
            public void describeTo(Description description) {
                description.appendText(String.format("does not contain [%s]", expectedStatuses));
            }
        };
    }
}
