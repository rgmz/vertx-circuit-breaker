package io.vertx.circuitbreaker.impl;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.HystrixMetricHandler;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jayway.awaitility.Awaitility.await;
import static io.vertx.circuitbreaker.asserts.Assertions.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
@RunWith(VertxUnitRunner.class)
public class HystrixMetricEventStreamTest {


  private CircuitBreaker breakerA;
  private CircuitBreaker breakerB;
  private CircuitBreaker breakerC;


  private Vertx vertx;

  @Before
  public void setUp(TestContext tc) {
    vertx = Vertx.vertx();
    vertx.exceptionHandler(tc.exceptionHandler());
  }

  @After
  public void tearDown() {
    if (breakerA != null) {
      breakerA.close();
    }
    if (breakerB != null) {
      breakerB.close();
    }
    if (breakerC != null) {
      breakerC.close();
    }

    AtomicBoolean completed = new AtomicBoolean();
    vertx.close(ar -> completed.set(ar.succeeded()));
    await().untilAtomic(completed, is(true));
  }


  @Test
  public void test() {
    breakerA = CircuitBreaker.create("A", vertx, new CircuitBreakerOptions().setTimeout(1000));
    breakerB = CircuitBreaker.create("B", vertx, new CircuitBreakerOptions().setTimeout(1000));
    breakerC = CircuitBreaker.create("C", vertx, new CircuitBreakerOptions().setTimeout(1000));

    Router router = Router.router(vertx);
    router.get("/metrics").handler(HystrixMetricHandler.create(vertx));

    AtomicBoolean ready = new AtomicBoolean();
    vertx.createHttpServer()
      .requestHandler(router::accept)
      .listen(8080, ar -> ready.set(ar.succeeded()));

    await().untilAtomic(ready, is(true));

    List<JsonObject> responses = new CopyOnWriteArrayList<>();
    HttpClient client = vertx.createHttpClient();
    client.get(8080, "localhost", "/metrics")
      .handler(response -> {
        response.handler(buffer -> {
          if (buffer.toString().startsWith("data:")) {
            String json = buffer.toString().substring("data:".length());
            responses.add(new JsonObject(json));
          }
        });
      }).end();

    for (int i = 0; i < 100; i++) {
      breakerA.execute(choose());
      breakerB.execute(choose());
      breakerC.execute(choose());
    }

    await().until(() -> responses.size() > 50);

    // Check that we got metrics for A, B and C
    JsonObject a = null;
    JsonObject b = null;
    JsonObject c = null;
    for (JsonObject json : responses) {
      if (json.getString("name").equals("A")) {
        a = json;
      } else if (json.getString("name").equals("B")) {
        b = json;
      } else if (json.getString("name").equals("C")) {
        c = json;
      }
    }

    assertThat(a).isNotNull();
    assertThat(b).isNotNull();
    assertThat(c).isNotNull();
  }


  private Random random = new Random();

  private Handler<Future<Void>> choose() {
    int choice = random.nextInt(5);
    switch (choice) {
      case 0:
        return commandThatWorks();
      case 1:
        return commandThatFails();
      case 2:
        return commandThatCrashes();
      case 3:
        return commandThatTimeout(1000);
      case 4:
        return commandThatTimeoutAndFail(1000);
    }
    return commandThatWorks();
  }


  private Handler<Future<Void>> commandThatWorks() {
    return (future -> vertx.setTimer(5, l -> future.complete(null)));
  }

  private Handler<Future<Void>> commandThatFails() {
    return (future -> vertx.setTimer(5, l -> future.fail("expected failure")));
  }

  private Handler<Future<Void>> commandThatCrashes() {
    return (future -> {
      throw new RuntimeException("Expected error");
    });
  }

  private Handler<Future<Void>> commandThatTimeout(int timeout) {
    return (future -> vertx.setTimer(timeout + 500, l -> future.complete(null)));
  }

  private Handler<Future<Void>> commandThatTimeoutAndFail(int timeout) {
    return (future -> vertx.setTimer(timeout + 500, l -> future.fail("late failure")));
  }


}