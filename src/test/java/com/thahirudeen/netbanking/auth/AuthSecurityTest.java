package com.thahirudeen.netbanking.auth;

import com.thahirudeen.netbanking.support.AbstractIntegrationTest;
import com.thahirudeen.netbanking.user.Role;
import com.thahirudeen.netbanking.user.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthSecurityTest extends AbstractIntegrationTest {

    private static String registerBody(String email, String password) {
        return "{\"name\":\"Test\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void registerReturnsATokenAndATwelveDigitAccountNumber() throws Exception {
        postJson("/api/auth/register", null, registerBody(uniqueEmail(), "password123"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", not(emptyOrNullString())))
                .andExpect(jsonPath("$.accountNumber", matchesPattern("\\d{12}")));
    }

    @Test
    void duplicateEmailIsRejectedEvenWithDifferentCase() throws Exception {
        String email = uniqueEmail();
        postJson("/api/auth/register", null, registerBody(email, "password123"), null).andExpect(status().isCreated());

        postJson("/api/auth/register", null, registerBody(email.toUpperCase(), "password123"), null)
                .andExpect(status().isConflict());
    }

    @Test
    void invalidRegistrationReturns400WithFieldDetails() throws Exception {
        postJson("/api/auth/register", null, registerBody("not-an-email", "short"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.email").exists())
                .andExpect(jsonPath("$.details.password").exists());
    }

    @Test
    void passwordIsStoredAsABcryptHashNeverAsPlainText() throws Exception {
        String email = uniqueEmail();
        registerViaApi(email);

        String stored = userRepository.findByEmail(email).orElseThrow().getPasswordHash();

        assertThat(stored).isNotEqualTo("password123");
        assertThat(stored).startsWith("$2"); // BCrypt prefix
    }

    @Test
    void loginWithTheRightPasswordReturnsAToken() throws Exception {
        String email = uniqueEmail();
        registerViaApi(email);

        postJson("/api/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"password123\"}", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyOrNullString())));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSame401() throws Exception {
        String email = uniqueEmail();
        registerViaApi(email);

        String wrongPassword = bodyOf(postJson("/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}", null)
                .andExpect(status().isUnauthorized()));
        String unknownEmail = bodyOf(postJson("/api/auth/login", null,
                "{\"email\":\"nobody-" + email + "\",\"password\":\"password123\"}", null)
                .andExpect(status().isUnauthorized()));

        // identical responses: an attacker cannot tell which emails are registered
        assertThat(wrongPassword).isEqualTo(unknownEmail);
    }

    @Test
    void everyProtectedEndpointRejectsRequestsWithoutAToken() throws Exception {
        getAs("/api/accounts/me", null).andExpect(status().isUnauthorized());
        getAs("/api/accounts/me/transactions", null).andExpect(status().isUnauthorized());
        postJson("/api/accounts/deposit", null, "{\"amount\":10}", null).andExpect(status().isUnauthorized());
        postJson("/api/accounts/withdraw", null, "{\"amount\":10}", null).andExpect(status().isUnauthorized());
        postJson("/api/transfers", null, "{\"toAccountNumber\":\"1\",\"amount\":10}", "k").andExpect(status().isUnauthorized());
        getAs("/api/admin/accounts", null).andExpect(status().isUnauthorized());
    }

    @Test
    void aGarbageTokenIsRejected() throws Exception {
        getAs("/api/accounts/me", "not.a.jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithATamperedSignatureIsRejected() throws Exception {
        String token = registerViaApi(uniqueEmail()).token();
        int sig = token.lastIndexOf('.') + 1;
        char flipped = token.charAt(sig) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, sig) + flipped + token.substring(sig + 1);

        getAs("/api/accounts/me", token).andExpect(status().isOk()); // the original works
        getAs("/api/accounts/me", tampered).andExpect(status().isUnauthorized());
    }

    @Test
    void aRoleSentInTheRegistrationBodyIsIgnored() throws Exception {
        String email = uniqueEmail();
        String body = "{\"name\":\"Mallory\",\"email\":\"" + email + "\",\"password\":\"password123\",\"role\":\"ADMIN\"}";

        postJson("/api/auth/register", null, body, null).andExpect(status().isCreated());

        User stored = userRepository.findByEmail(email).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(Role.USER);
        String token = loginViaApi(email, "password123").token();
        getAs("/api/admin/accounts", token).andExpect(status().isForbidden());
    }

    @Test
    void aNormalUserGets403OnEveryAdminEndpoint() throws Exception {
        String token = registerViaApi(uniqueEmail()).token();

        getAs("/api/admin/accounts", token).andExpect(status().isForbidden());
        postJson("/api/admin/accounts/1/freeze", token, "", null).andExpect(status().isForbidden());
        postJson("/api/admin/accounts/1/unfreeze", token, "", null).andExpect(status().isForbidden());
    }
}
