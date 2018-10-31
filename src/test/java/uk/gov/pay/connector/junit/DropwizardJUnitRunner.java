package uk.gov.pay.connector.junit;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.DropwizardTestSupport;
import junitparams.JUnitParamsRunner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.FrameworkField;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import uk.gov.pay.connector.util.PortFactory;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.Lists.newArrayList;
import static io.dropwizard.testing.ConfigOverride.config;
import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static java.util.Arrays.stream;
import static uk.gov.pay.connector.junit.DropwizardTestApplications.createIfNotRunning;
import static uk.gov.pay.connector.junit.PostgresTestContainer.DB_PASSWORD;
import static uk.gov.pay.connector.junit.PostgresTestContainer.DB_USERNAME;
import static uk.gov.pay.connector.junit.PostgresTestDocker.getDbUri;
import static uk.gov.pay.connector.junit.PostgresTestDocker.getOrCreate;

/**
 * Runs a Dropwizard application with the given {@link DropwizardConfig} before the Test class if there is not an
 * application started yet (from a previous Test class using the same runner) with the same {@link DropwizardConfig}.
 * <p>
 * Example:
 * <p>
 * <pre>
 *  &#64;RunWith(DropwizardJUnitRunner.class)
 *  &#64;DropwizardConfig(app = MyApp.class, config = "config/test.yaml")
 *  public class MyTest {
 *
 *      &#64;Test
 *      public void shouldGetTestResourceFromARunningApplicationInAnotherClass() throws Exception {
 *          given().get("/test-resource")
 *                 .then()
 *                 .statusCode(200);
 *      }
 *  }
 * </pre>
 * <p>
 * After this test and if more tests are being executed, the application will be kept alive so other tests that needs
 * similar configuration will benefit from the existing running application.
 */
public final class DropwizardJUnitRunner extends JUnitParamsRunner {

    public final static int WIREMOCK_PORT = PortFactory.findFreePort();

    public DropwizardJUnitRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
    }

    @Override
    protected Statement classBlock(final RunNotifier notifier) {
        DropwizardConfig dropwizardConfigAnnotation = dropwizardConfigAnnotation();
        List<ConfigOverride> configOverride = newArrayList();
        if (dropwizardConfigAnnotation.withDockerPostgres()) {
            getOrCreate(dropwizardConfigAnnotation.postgresDockerImage());
            configOverride.add(config("database.url", getDbUri()));
            configOverride.add(config("database.user", DB_USERNAME));
            configOverride.add(config("database.password", DB_PASSWORD));
        }
        configOverride.add(config("worldpay.urls.test", "http://localhost:" + WIREMOCK_PORT + "/jsp/merchant/xml/paymentService.jsp"));
        configOverride.add(config("smartpay.urls.test", "http://localhost:" + WIREMOCK_PORT + "/pal/servlet/soap/Payment"));
        configOverride.add(config("epdq.urls.test", "http://localhost:" + WIREMOCK_PORT + "/epdq"));
        configOverride.add(config("smartpay.urls.test", "http://localhost:" + WIREMOCK_PORT + "/pal/servlet/soap/Payment"));
        Optional<DropwizardTestSupport> createdApp = createIfNotRunning(dropwizardConfigAnnotation.app(), dropwizardConfigAnnotation.config(), configOverride.toArray(new ConfigOverride[0]));
        if (dropwizardConfigAnnotation.withDockerPostgres() && createdApp.isPresent()) {
            try {
                createdApp.get().getApplication().run("db", "migrate", resourceFilePath(dropwizardConfigAnnotation.config()));
            } catch (Exception e) {
                throw new DropwizardJUnitRunnerException(e);
            }
        }

        return super.classBlock(notifier);
    }

    @Override
    protected Statement withAfterClasses(Statement statement) {
        DropwizardConfig declaredConfiguration = dropwizardConfigAnnotation();
        TestContext testContext = DropwizardTestApplications.getTestContextOf(declaredConfiguration.app(), declaredConfiguration.config());
        testContext.getDatabaseTestHelper().truncateAllData();
        return super.withAfterClasses(statement);
    }

    @Override
    public Object createTest() throws Exception {
        Object testInstance = super.createTest();
        DropwizardConfig declaredConfiguration = dropwizardConfigAnnotation();
        TestContext testContext = DropwizardTestApplications.getTestContextOf(declaredConfiguration.app(), declaredConfiguration.config());
        setTestContextToDeclaredAnnotations(testInstance, testContext);
        return testInstance;
    }

    private void setTestContextToDeclaredAnnotations(Object testInstance, TestContext testContext) {
        List<FrameworkField> annotatedFields = getTestClass().getAnnotatedFields();
        annotatedFields.forEach(frameworkField -> stream(frameworkField.getAnnotations())
                .filter(annotation -> annotation.annotationType().equals(DropwizardTestContext.class))
                .findFirst()
                .ifPresent(testContextAnnotation -> {
                    frameworkField.getField().setAccessible(true);
                    try {
                        frameworkField.getField().set(testInstance, testContext);
                    } catch (IllegalAccessException e) {
                        throw new DropwizardJUnitRunnerException(e);
                    }
                }));
    }

    private DropwizardConfig dropwizardConfigAnnotation() {
        return (DropwizardConfig) stream(getTestClass().getAnnotations())
                .filter(annotation -> annotation.annotationType().equals(DropwizardConfig.class))
                .findFirst()
                .orElseThrow(() -> new DropwizardJUnitRunnerException("DropwizardJUnitRunner requires annotation @DropwizardConfig to be present"));
    }
}