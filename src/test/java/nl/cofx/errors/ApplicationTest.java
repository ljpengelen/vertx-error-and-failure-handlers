package nl.cofx.errors;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
@Slf4j
class ApplicationTest {

    private static final int PORT = 8080;
    private static final String REQUEST_HANDLER_ERROR_MESSAGE = "Something went wrong in request handler";
    private static final String FAILURE_HANDLER_ERROR_MESSAGE = "Something went wrong in failure handler";
    private static final String INTERNAL_SERVER_ERROR = "Internal Server Error";
    private static final String MESSAGE_FROM_ERROR_HANDLER = "Message from error handler";
    private static final String MESSAGE_FROM_FAILURE_HANDLER = "Message from failure handler";

    private Router router;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext vertxTestContext) {
        router = Router.router(vertx);

        var httpServer = vertx.createHttpServer();
        httpServer.requestHandler(router);
        httpServer.listen(PORT, asyncServer -> {
            if (asyncServer.succeeded()) {
                log.info("Listening for HTTP requests on port {}", PORT);
                vertxTestContext.completeNow();
            } else {
                log.error("Failed to listen for HTTP requests on port {}", PORT, asyncServer.cause());
                vertxTestContext.failNow(asyncServer.cause());
            }
        });
    }

    @Test
    void failureHandlerCanHandleThrownException(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var failureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                })
                .failureHandler(rc -> {
                    failureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(MESSAGE_FROM_FAILURE_HANDLER + ": " + rc.failure().getMessage());
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).startsWith(MESSAGE_FROM_FAILURE_HANDLER);
        assertThat(response.body()).endsWith(REQUEST_HANDLER_ERROR_MESSAGE);
    }

    @Test
    void failureHandlerCanHandleFailWithStatusCode(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var failureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    rc.fail(418);
                })
                .failureHandler(rc -> {
                    failureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end();
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(418);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void failureHandlerCanHandleFailWithStatusCodeAndException(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var failureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    rc.fail(418, new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE));
                })
                .failureHandler(rc -> {
                    failureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(MESSAGE_FROM_FAILURE_HANDLER + ": " + rc.failure().getMessage());
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(418);
        assertThat(response.body()).startsWith(MESSAGE_FROM_FAILURE_HANDLER);
        assertThat(response.body()).endsWith(REQUEST_HANDLER_ERROR_MESSAGE);
    }

    @Test
    void errorHandlerIsIgnoredWhenFailureHandlerHandledFailure(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var failureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                })
                .failureHandler(rc -> {
                    failureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(MESSAGE_FROM_FAILURE_HANDLER + ": " + rc.failure().getMessage());
                });
        router.errorHandler(500, rc -> vertxTestContext.failNow("Error should not reach error handler"));

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).startsWith(MESSAGE_FROM_FAILURE_HANDLER);
        assertThat(response.body()).endsWith(REQUEST_HANDLER_ERROR_MESSAGE);
    }

    @Test
    void errorHandlerCanHandleException(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var errorHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                });
        router.errorHandler(500, rc -> {
            errorHandlerExecuted.flag();
            rc.response()
                    .setStatusCode(500)
                    .end(MESSAGE_FROM_ERROR_HANDLER + ": " + rc.failure().getMessage());
        });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).startsWith(MESSAGE_FROM_ERROR_HANDLER);
        assertThat(response.body()).endsWith(REQUEST_HANDLER_ERROR_MESSAGE);
    }

    @Test
    void exceptionWithoutErrorOrFailureHandlerLeadsToInternalServerError(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(INTERNAL_SERVER_ERROR);
    }

    @Test
    void failureHandlerCanDeferToNextFailureHandler(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var firstFailureHandlerExecuted = vertxTestContext.checkpoint();
        var secondFailureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                })
                .failureHandler(rc -> {
                    firstFailureHandlerExecuted.flag();
                    rc.next();
                })
                .failureHandler(rc -> {
                    secondFailureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(MESSAGE_FROM_FAILURE_HANDLER + ": " + rc.failure().getMessage());
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).startsWith(MESSAGE_FROM_FAILURE_HANDLER);
        assertThat(response.body()).endsWith(REQUEST_HANDLER_ERROR_MESSAGE);
    }

    @Test
    void exceptionInFailureHandlerIsNotHandledByNextFailureHandler(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var firstFailureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                })
                .failureHandler(rc -> {
                    firstFailureHandlerExecuted.flag();
                    throw new RuntimeException(FAILURE_HANDLER_ERROR_MESSAGE);
                })
                .failureHandler(rc -> vertxTestContext.failNow("Error should not reach second failure handler"));

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(INTERNAL_SERVER_ERROR);
    }

    @Test
    void exceptionInFailureHandlerIsIgnoredByErrorHandler(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var failureHandlerExecuted = vertxTestContext.checkpoint();
        var errorHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                })
                .failureHandler(rc -> {
                    failureHandlerExecuted.flag();
                    throw new RuntimeException(FAILURE_HANDLER_ERROR_MESSAGE);
                });

        router.errorHandler(500, rc -> {
            errorHandlerExecuted.flag();
            rc.response()
                    .setStatusCode(500)
                    .end(MESSAGE_FROM_ERROR_HANDLER + ": " + rc.failure().getMessage());
        });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).startsWith(MESSAGE_FROM_ERROR_HANDLER);
        assertThat(response.body()).endsWith(REQUEST_HANDLER_ERROR_MESSAGE);
    }

    @Test
    void errorHandlerForSubRouterIsIgnored(Vertx vertx, VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var rootErrorHandlerExecuted = vertxTestContext.checkpoint();

        var subRouter = Router.router(vertx);
        subRouter.errorHandler(500, rc ->
                vertxTestContext.failNow("Error handler for sub router should not be reached"));
        subRouter.route("/route")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                });

        router.route("/sub/*")
                .subRouter(subRouter);

        router.errorHandler(500, rc -> {
            rootErrorHandlerExecuted.flag();
            rc.response()
                    .setStatusCode(500)
                    .end(MESSAGE_FROM_ERROR_HANDLER + ": " + rc.failure().getMessage());
        });

        var response = performGetRequest("/sub/route");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).startsWith(MESSAGE_FROM_ERROR_HANDLER);
        assertThat(response.body()).endsWith(REQUEST_HANDLER_ERROR_MESSAGE);
    }

    @Test
    void failureHandlerForSubRouterCanFallBackToFailureHandlerForRoot(Vertx vertx, VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var rootFailureHandlerExecuted = vertxTestContext.checkpoint();
        var subFailureHandlerExecuted = vertxTestContext.checkpoint();

        var subRouter = Router.router(vertx);
        subRouter.route("/route")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                })
                .failureHandler(rc -> {
                    subFailureHandlerExecuted.flag();
                    rc.next();
                });

        router.route("/sub/*")
                .subRouter(subRouter);

        router.route()
                .failureHandler(rc -> {
                    rootFailureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(500)
                            .end(MESSAGE_FROM_FAILURE_HANDLER + ": " + rc.failure().getMessage());
                });

        var response = performGetRequest("/sub/route");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).startsWith(MESSAGE_FROM_FAILURE_HANDLER);
        assertThat(response.body()).endsWith(REQUEST_HANDLER_ERROR_MESSAGE);
    }

    @Test
    void failureHandlerForSubRouterCanProduceResult(Vertx vertx, VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var subFailureHandlerExecuted = vertxTestContext.checkpoint();

        var subRouter = Router.router(vertx);
        subRouter.route("/route")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
                })
                .failureHandler(rc -> {
                    subFailureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(500)
                            .end(FAILURE_HANDLER_ERROR_MESSAGE + ": " + rc.failure().getMessage());
                });

        router.route("/sub/*")
                .subRouter(subRouter);

        router.route()
                .failureHandler(rc -> vertxTestContext.failNow("Failure handler for root route should not be reached"));

        var response = performGetRequest("/sub/route");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).startsWith(FAILURE_HANDLER_ERROR_MESSAGE);
        assertThat(response.body()).endsWith(REQUEST_HANDLER_ERROR_MESSAGE);
    }

    @SneakyThrows
    private static HttpResponse<String> performGetRequest(String path) {
        var httpClient = HttpClient.newHttpClient();
        return httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080" + path))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
