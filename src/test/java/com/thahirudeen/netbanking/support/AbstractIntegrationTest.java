package com.thahirudeen.netbanking.support;

import com.jayway.jsonpath.JsonPath;
import com.thahirudeen.netbanking.account.AccountRepository;
import com.thahirudeen.netbanking.account.AccountService;
import com.thahirudeen.netbanking.auth.AuthService;
import com.thahirudeen.netbanking.auth.RegisterRequest;
import com.thahirudeen.netbanking.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base class for tests that need the real application against a real Postgres.
 * One container is started once per test run and shared by every subclass,
 * so Spring reuses a single application context too.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start(); // stopped automatically when the test JVM exits
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected AuthService authService;
    @Autowired protected AccountService accountService;
    @Autowired protected UserRepository userRepository;
    @Autowired protected AccountRepository accountRepository;

    // ---- data helpers -------------------------------------------------------------

    protected record TestUser(Long userId, Long accountId, String accountNumber, String email) {}

    /** Creates a user and account through the real service, optionally pre-funded. */
    protected TestUser newUser(String startingBalance) {
        String email = "u" + UUID.randomUUID() + "@test.com";
        String accountNumber = authService.register(new RegisterRequest("Test User", email, "password123")).accountNumber();
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        Long accountId = accountRepository.findByAccountNumber(accountNumber).orElseThrow().getId();
        BigDecimal amount = new BigDecimal(startingBalance);
        if (amount.signum() > 0) {
            accountService.deposit(userId, amount);
        }
        return new TestUser(userId, accountId, accountNumber, email);
    }

    protected BigDecimal balanceOf(TestUser u) {
        return accountRepository.findById(u.accountId()).orElseThrow().getBalance();
    }

    // ---- HTTP helpers -------------------------------------------------------------

    protected record Session(String token, String accountNumber) {}

    protected Session registerViaApi(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"T\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return new Session(JsonPath.read(body, "$.token"), JsonPath.read(body, "$.accountNumber"));
    }

    protected Session loginViaApi(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return new Session(JsonPath.read(body, "$.token"), JsonPath.read(body, "$.accountNumber"));
    }

    protected String uniqueEmail() {
        return "u" + UUID.randomUUID() + "@test.com";
    }

    protected ResultActions postJson(String url, String token, String body, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    protected ResultActions getAs(String url, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    protected static String bodyOf(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    protected static double number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).doubleValue();
    }

    // ---- concurrency helper -------------------------------------------------------

    protected record Outcome<T>(T value, Throwable error) {
        public boolean succeeded() { return error == null; }
    }

    /**
     * Runs one task per thread, releasing all of them at the same instant.
     * A hang (for example a deadlock) fails the test after 60 seconds instead of freezing it.
     */
    protected <T> List<Outcome<T>> runConcurrently(int threads, IntFunction<Callable<T>> taskFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<T> task = taskFor.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome<>(null, t);
                    }
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("threads did not start in time");
            }
            go.countDown();
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
