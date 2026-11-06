package com.thahirudeen.netbanking;

import com.thahirudeen.netbanking.support.AbstractIntegrationTest;
import com.thahirudeen.netbanking.user.Role;
import com.thahirudeen.netbanking.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end behaviour through the real HTTP layer, security filters and a real Postgres. */
class BankingFlowTest extends AbstractIntegrationTest {

    @Autowired PasswordEncoder passwordEncoder;

    private Session newCustomer() throws Exception {
        return registerViaApi(uniqueEmail());
    }

    private Session newAdmin() throws Exception {
        String email = uniqueEmail();
        User admin = new User();
        admin.setName("Admin");
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode("adminpass123"));
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
        return loginViaApi(email, "adminpass123");
    }

    private static String transferBody(String to, String amount) {
        return "{\"toAccountNumber\":\"" + to + "\",\"amount\":" + amount + "}";
    }

    private double balance(Session s) throws Exception {
        return number(bodyOf(getAs("/api/accounts/me", s.token()).andExpect(status().isOk())), "$.balance");
    }

    // ---- deposit and withdraw ------------------------------------------------------

    @Test
    void depositAndWithdrawChangeTheBalance() throws Exception {
        Session alice = newCustomer();

        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":100.00}", null).andExpect(status().isOk());
        postJson("/api/accounts/withdraw", alice.token(), "{\"amount\":40.25}", null).andExpect(status().isOk());

        assertThat(balance(alice)).isEqualTo(59.75);
    }

    @Test
    void invalidAmountsAreRejectedWith400() throws Exception {
        Session alice = newCustomer();

        for (String bad : new String[]{"{\"amount\":-5}", "{\"amount\":0}", "{\"amount\":1.001}", "{}"}) {
            postJson("/api/accounts/deposit", alice.token(), bad, null).andExpect(status().isBadRequest());
            postJson("/api/accounts/withdraw", alice.token(), bad, null).andExpect(status().isBadRequest());
        }
        assertThat(balance(alice)).isEqualTo(0.0);
    }

    @Test
    void overdrawingReturns422AndKeepsTheBalance() throws Exception {
        Session alice = newCustomer();
        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":50}", null).andExpect(status().isOk());

        postJson("/api/accounts/withdraw", alice.token(), "{\"amount\":50.01}", null)
                .andExpect(status().isUnprocessableEntity());

        assertThat(balance(alice)).isEqualTo(50.0);
    }

    // ---- transfers and history -----------------------------------------------------

    @Test
    void transferMovesMoneyAndBothSidesSeeItInTheirHistory() throws Exception {
        Session alice = newCustomer();
        Session bob = newCustomer();
        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":100}", null).andExpect(status().isOk());

        postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "30"), "k-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromAccount").value(alice.accountNumber()))
                .andExpect(jsonPath("$.toAccount").value(bob.accountNumber()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertThat(balance(alice)).isEqualTo(70.0);
        assertThat(balance(bob)).isEqualTo(30.0);

        // newest first: the transfer, then the deposit (which has no sender)
        getAs("/api/accounts/me/transactions", alice.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$[1].type").value("DEPOSIT"))
                .andExpect(jsonPath("$[1].fromAccount").isEmpty());
        getAs("/api/accounts/me/transactions", bob.token())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("TRANSFER"));
    }

    @Test
    void historyOnlyEverShowsTheCallersOwnTransactions() throws Exception {
        Session alice = newCustomer();
        Session bob = newCustomer();
        Session carol = newCustomer();
        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":100}", null).andExpect(status().isOk());
        postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "10"), "k-1").andExpect(status().isOk());

        getAs("/api/accounts/me/transactions", carol.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void historyIsPagedAndTheSizeIsClamped() throws Exception {
        Session alice = newCustomer();
        for (int i = 0; i < 3; i++) {
            postJson("/api/accounts/deposit", alice.token(), "{\"amount\":1}", null).andExpect(status().isOk());
        }

        getAs("/api/accounts/me/transactions?size=2", alice.token()).andExpect(jsonPath("$", hasSize(2)));
        getAs("/api/accounts/me/transactions?size=2&page=1", alice.token()).andExpect(jsonPath("$", hasSize(1)));
        getAs("/api/accounts/me/transactions?size=0", alice.token()).andExpect(jsonPath("$", hasSize(1))); // clamped to 1
    }

    @Test
    void badTransfersAreRejectedWithTheRightStatus() throws Exception {
        Session alice = newCustomer();
        Session bob = newCustomer();
        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":50}", null).andExpect(status().isOk());

        postJson("/api/transfers", alice.token(), transferBody(alice.accountNumber(), "5"), "k-self")
                .andExpect(status().isBadRequest());
        postJson("/api/transfers", alice.token(), transferBody("999999999999", "5"), "k-unknown")
                .andExpect(status().isNotFound());
        postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "999"), "k-big")
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "-5"), "k-neg")
                .andExpect(status().isBadRequest());
        postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "5"), null)
                .andExpect(status().isBadRequest()); // Idempotency-Key header is required

        assertThat(balance(alice)).isEqualTo(50.0);
        assertThat(balance(bob)).isEqualTo(0.0);
    }

    // ---- idempotency over HTTP -----------------------------------------------------

    @Test
    void retryingATransferWithTheSameKeyReturnsTheSameResultAndMovesMoneyOnce() throws Exception {
        Session alice = newCustomer();
        Session bob = newCustomer();
        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":100}", null).andExpect(status().isOk());

        String first = bodyOf(postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "25"), "retry-key")
                .andExpect(status().isOk()));
        String second = bodyOf(postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "25"), "retry-key")
                .andExpect(status().isOk()));

        assertThat(number(second, "$.transactionId")).isEqualTo(number(first, "$.transactionId"));
        assertThat(balance(alice)).isEqualTo(75.0);
        assertThat(balance(bob)).isEqualTo(25.0);
    }

    @Test
    void reusingAKeyForADifferentAmountIsRejectedWith409() throws Exception {
        Session alice = newCustomer();
        Session bob = newCustomer();
        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":100}", null).andExpect(status().isOk());
        postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "25"), "same-key").andExpect(status().isOk());

        postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "99"), "same-key")
                .andExpect(status().isConflict());

        assertThat(balance(alice)).isEqualTo(75.0);
    }

    @Test
    void twoUsersMayUseTheSameKeyValueIndependently() throws Exception {
        Session alice = newCustomer();
        Session bob = newCustomer();
        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":100}", null).andExpect(status().isOk());
        postJson("/api/accounts/deposit", bob.token(), "{\"amount\":100}", null).andExpect(status().isOk());

        postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "10"), "shared-key").andExpect(status().isOk());
        postJson("/api/transfers", bob.token(), transferBody(alice.accountNumber(), "10"), "shared-key").andExpect(status().isOk());

        assertThat(balance(alice)).isEqualTo(100.0); // -10 sent, +10 received
        assertThat(balance(bob)).isEqualTo(100.0);
    }

    // ---- admin ---------------------------------------------------------------------

    @Test
    void adminCanListAccounts() throws Exception {
        newCustomer();
        Session admin = newAdmin();

        getAs("/api/admin/accounts?size=5", admin.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ownerEmail").exists())
                .andExpect(jsonPath("$[0].accountNumber").exists())
                .andExpect(jsonPath("$[0].status").exists());
    }

    @Test
    void aFrozenAccountCannotMoveMoneyInOrOutButCanStillBeRead() throws Exception {
        Session alice = newCustomer();
        Session bob = newCustomer();
        Session admin = newAdmin();
        Long aliceId = accountRepository.findByAccountNumber(alice.accountNumber()).orElseThrow().getId();
        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":100}", null).andExpect(status().isOk());

        postJson("/api/admin/accounts/" + aliceId + "/freeze", admin.token(), "", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":10}", null).andExpect(status().isForbidden());
        postJson("/api/accounts/withdraw", alice.token(), "{\"amount\":10}", null).andExpect(status().isForbidden());
        postJson("/api/transfers", alice.token(), transferBody(bob.accountNumber(), "10"), "f-1").andExpect(status().isForbidden());
        postJson("/api/transfers", bob.token(), transferBody(alice.accountNumber(), "10"), "f-2").andExpect(status().isForbidden());
        getAs("/api/accounts/me", alice.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        assertThat(balance(alice)).isEqualTo(100.0);

        postJson("/api/admin/accounts/" + aliceId + "/unfreeze", admin.token(), "", null)
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        postJson("/api/accounts/deposit", alice.token(), "{\"amount\":10}", null).andExpect(status().isOk());
        assertThat(balance(alice)).isEqualTo(110.0);
    }

    @Test
    void freezingTwiceIsHarmlessAndAnUnknownAccountIs404() throws Exception {
        Session alice = newCustomer();
        Session admin = newAdmin();
        Long aliceId = accountRepository.findByAccountNumber(alice.accountNumber()).orElseThrow().getId();

        postJson("/api/admin/accounts/" + aliceId + "/freeze", admin.token(), "", null).andExpect(status().isOk());
        postJson("/api/admin/accounts/" + aliceId + "/freeze", admin.token(), "", null).andExpect(status().isOk());
        postJson("/api/admin/accounts/999999999/freeze", admin.token(), "", null).andExpect(status().isNotFound());
    }
}
