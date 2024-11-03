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
    private static final RuntimeException REQUEST_HANDLER_EXCEPTION =
            new RuntimeException(REQUEST_HANDLER_ERROR_MESSAGE);
    private static final String FAILURE_HANDLER_ERROR_MESSAGE = "Something went wrong in failure handler";
    private static final RuntimeException FAILURE_HANDLER_EXCEPTION =
            new RuntimeException(FAILURE_HANDLER_ERROR_MESSAGE);
    private static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

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
                    throw REQUEST_HANDLER_EXCEPTION;
                })
                .failureHandler(rc -> {
                    failureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(rc.failure().getMessage());
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
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
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void failureHandlerCanHandleFailWithStatusCodeAndException(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var failureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    rc.fail(418, REQUEST_HANDLER_EXCEPTION);
                })
                .failureHandler(rc -> {
                    failureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(rc.failure().getMessage());
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(418);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void errorHandlerIsIgnoredWhenFailureHandlerHandledFailure(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var failureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                })
                .failureHandler(rc -> {
                    failureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(rc.failure().getMessage());
                });
        router.errorHandler(500, rc -> vertxTestContext.failNow("Error should not reach error handler"));

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void failureHandlerCanDeferToFailureHandlerOfOtherMatchingRoute(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var firstFailureHandlerExecuted = vertxTestContext.checkpoint();
        var secondFailureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                })
                .failureHandler(rc -> {
                    firstFailureHandlerExecuted.flag();
                    rc.next();
                });
        router.route()
                .failureHandler(rc -> {
                    secondFailureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(rc.failure().getMessage());
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void failureCanBeHandledByFailureHandlerOfOtherMatchingRoute(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var failureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                });
        router.route()
                .failureHandler(rc -> {
                    failureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(rc.failure().getMessage());
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void errorHandlerCanHandleException(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var errorHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                });
        router.errorHandler(500, rc -> {
            errorHandlerExecuted.flag();
            rc.response()
                    .setStatusCode(500)
                    .end(rc.failure().getMessage());
        });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void exceptionWithoutErrorOrFailureHandlerLeadsToInternalServerError(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(INTERNAL_SERVER_ERROR);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void failureHandlerCanDeferToNextFailureHandler(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var firstFailureHandlerExecuted = vertxTestContext.checkpoint();
        var secondFailureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                })
                .failureHandler(rc -> {
                    firstFailureHandlerExecuted.flag();
                    rc.next();
                })
                .failureHandler(rc -> {
                    secondFailureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(rc.failure().getMessage());
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void exceptionInFailureHandlerIsNotHandledByNextFailureHandler(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var firstFailureHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                })
                .failureHandler(rc -> {
                    firstFailureHandlerExecuted.flag();
                    throw FAILURE_HANDLER_EXCEPTION;
                })
                .failureHandler(rc -> {
                    vertxTestContext.failNow("Error should not reach second failure handler");
                    rc.response()
                            .setStatusCode(rc.statusCode())
                            .end(rc.failure().getMessage());
                });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(INTERNAL_SERVER_ERROR);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void exceptionInFailureHandlerIsIgnoredByErrorHandler(VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var firstFailureHandlerExecuted = vertxTestContext.checkpoint();
        var errorHandlerExecuted = vertxTestContext.checkpoint();

        router.route("/")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                })
                .failureHandler(rc -> {
                    firstFailureHandlerExecuted.flag();
                    throw FAILURE_HANDLER_EXCEPTION;
                });

        router.errorHandler(500, rc -> {
            errorHandlerExecuted.flag();
            rc.response()
                    .setStatusCode(500)
                    .end(rc.failure().getMessage());
        });

        var response = performGetRequest("/");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void errorHandlerForSubRouterIsIgnored(Vertx vertx, VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var rootErrorHandlerExecuted = vertxTestContext.checkpoint();

        var subRouter = Router.router(vertx);
        subRouter.errorHandler(500, rc -> {
            vertxTestContext.failNow("Error handler for sub router should not be reached");
            rc.response()
                    .setStatusCode(500)
                    .end(REQUEST_HANDLER_ERROR_MESSAGE);
        });
        subRouter.route("/route")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                });

        router.route("/sub/*")
                .subRouter(subRouter);

        router.errorHandler(500, rc -> {
            rootErrorHandlerExecuted.flag();
            rc.response()
                    .setStatusCode(500)
                    .end(REQUEST_HANDLER_ERROR_MESSAGE);
        });

        var response = performGetRequest("/sub/route");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void failureHandlerForSubRouterCanFallBackToErrorHandlerForRoot(Vertx vertx, VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var rootFailureHandlerExecuted = vertxTestContext.checkpoint();
        var subFailureHandlerExecuted = vertxTestContext.checkpoint();

        var subRouter = Router.router(vertx);
        subRouter.route("/route")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
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
                            .end(REQUEST_HANDLER_ERROR_MESSAGE);
                });

        var response = performGetRequest("/sub/route");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @Test
    void failureHandlerForSubRouterCanProduceResult(Vertx vertx, VertxTestContext vertxTestContext) {
        var handlerExecuted = vertxTestContext.checkpoint();
        var subFailureHandlerExecuted = vertxTestContext.checkpoint();

        var subRouter = Router.router(vertx);
        subRouter.route("/route")
                .handler(rc -> {
                    handlerExecuted.flag();
                    throw REQUEST_HANDLER_EXCEPTION;
                })
                .failureHandler(rc -> {
                    subFailureHandlerExecuted.flag();
                    rc.response()
                            .setStatusCode(500)
                            .end(REQUEST_HANDLER_ERROR_MESSAGE);
                });

        router.route("/sub/*")
                .subRouter(subRouter);

        router.route()
                .failureHandler(rc -> vertxTestContext.failNow("Failure handler for root route should not be reached"));

        var response = performGetRequest("/sub/route");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo(REQUEST_HANDLER_ERROR_MESSAGE);
        vertxTestContext.succeedingThenComplete();
    }

    @SneakyThrows
    private static HttpResponse<String> performGetRequest(String path) {
        var httpClient = HttpClient.newHttpClient();
        return httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080" + path))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
