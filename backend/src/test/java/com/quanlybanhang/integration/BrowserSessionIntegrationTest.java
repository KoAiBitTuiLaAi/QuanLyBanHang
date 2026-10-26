package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:browser_sessions;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.show-sql=false", "app.jwt.secret=browser-session-test-secret-at-least-32-characters",
    "app.bootstrap.admin-password=BrowserTest2026!", "app.bootstrap.admin-email=browser@example.invalid",
    "app.seed-demo-users=false", "app.seed-demo-data=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class BrowserSessionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;
    @Autowired RolePermissionConfig matrix;
    @Autowired JwtUtils jwt;
    String username;
    final String origin="http://localhost:5500", password="BrowserTest2026!";

    @BeforeEach void setup() {
        username="bt"+UUID.randomUUID().toString().substring(0,8);
        users.saveAndFlush(User.builder().username(username).email(username+"@example.invalid")
                .password(encoder.encode(password)).fullName("Browser Test")
                .roles(Set.of(roles.findByName(ERole.ROLE_VIEWER).orElseThrow())).build());
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.PRODUCT_VIEW));
    }
    MockHttpServletRequestBuilder browser(MockHttpServletRequestBuilder request) {
        return request.header("Origin",origin).header("X-Browser-Session","1").contentType(MediaType.APPLICATION_JSON);
    }
    MvcResult login() throws Exception {
        var result=mvc.perform(browser(post("/api/auth/login")).content(json.writeValueAsString(
                Map.of("username",username,"password",password)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result;
    }
    String access(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).path("accessToken").asText(); }
    Cookie cookie(MvcResult result) { return result.getResponse().getCookie("qlbh_refresh"); }
    MvcResult restore(Cookie cookie) throws Exception {
        return mvc.perform(browser(post("/api/auth/refresh")).cookie(cookie).content("{}")).andReturn();
    }
    int me(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus();
    }

    @Test void browserLoginSetsScopedHttpOnlyCookieAndNeverReturnsRefreshCredential() throws Exception {
        var result=login();
        String header=result.getResponse().getHeader("Set-Cookie");
        assertThat(header).contains("HttpOnly","SameSite=Strict","Path=/api/auth").doesNotContain("Max-Age");
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("refreshToken").isNull()).isTrue();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(jwt.getSessionId(access(result))).isEqualTo(jwt.getSessionId(cookie(result).getValue()));
    }
    @Test void emptyNewTabCanRestoreAndConcurrentTabsDoNotRevokeEachOther() throws Exception {
        Cookie cookie=cookie(login());
        var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->restore(cookie));
            var second=executor.submit(()->restore(cookie));
            for(var result:List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))) {
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                JsonNode data=json.readTree(result.getResponse().getContentAsString());
                assertThat(data.path("username").asText()).isEqualTo(username);
                assertThat(data.path("refreshToken").isNull()).isTrue();
                assertThat(me(access(result))).isEqualTo(200);
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void logoutRevokesAccessTokensAlreadyIssuedToEveryTabAndClearsCookie() throws Exception {
        var first=login(); Cookie cookie=cookie(first);
        var second=restore(cookie);
        var otherDevice=login();
        assertThat(me(access(second))).isEqualTo(200);
        var logout=mvc.perform(browser(post("/api/auth/logout")).cookie(cookie)
                .header("Authorization","Bearer "+access(first)).content("{}")).andReturn();
        assertThat(logout.getResponse().getStatus()).isEqualTo(200);
        assertThat(logout.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0","Path=/api/auth","HttpOnly","SameSite=Strict");
        assertThat(me(access(first))).isEqualTo(401);
        assertThat(me(access(second))).isEqualTo(401);
        assertThat(restore(cookie).getResponse().getStatus()).isEqualTo(401);
        assertThat(me(access(otherDevice))).isEqualTo(200);
        assertThat(restore(cookie(otherDevice)).getResponse().getStatus()).isEqualTo(200);
    }
    @Test void cookieAloneNeverAuthorizesBusinessApiAndRequiresExplicitHeaderForRestore() throws Exception {
        Cookie cookie=cookie(login());
        assertThat(mvc.perform(get("/api/products").cookie(cookie)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/auth/refresh").header("Origin",origin).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/auth/refresh").header("X-Browser-Session","1").cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
    @Test void untrustedOriginCannotRefreshOrLogoutAndBadCookieIsCleared() throws Exception {
        var logged=login(); Cookie cookie=cookie(logged);
        for(String action:List.of("refresh","logout")) {
            assertThat(mvc.perform(post("/api/auth/"+action).header("Origin","https://untrusted.invalid")
                    .header("X-Browser-Session","1").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(me(access(logged))).isEqualTo(200);
        var bad=restore(new Cookie("qlbh_refresh","bad-token"));
        assertThat(bad.getResponse().getStatus()).isEqualTo(401);
        assertThat(bad.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0","Path=/api/auth");
        String token=cookie.getValue();
        int signature=token.lastIndexOf('.')+1;
        String tampered=token.substring(0,signature)+(token.charAt(signature)=='A'?'B':'A')+token.substring(signature+1);
        assertThat(restore(new Cookie("qlbh_refresh",tampered)).getResponse().getStatus()).isEqualTo(401);
    }
    @Test void sameJwtImmediatelyLosesAndRegainsDbMatrixPermissionAfterRestore() throws Exception {
        var restored=restore(cookie(login())); String token=access(restored);
        assertThat(mvc.perform(get("/api/products").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus()).isEqualTo(200);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of());
        assertThat(mvc.perform(get("/api/products").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus()).isEqualTo(403);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.PRODUCT_VIEW));
        assertThat(mvc.perform(get("/api/products").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
    @Test void accountLockAndPasswordVersionChangeInvalidateCookieAndAccess() throws Exception {
        var logged=login(); Cookie cookie=cookie(logged);
        var user=users.findByUsername(username).orElseThrow();
        user.setTokenVersion(user.getTokenVersion()+1); users.saveAndFlush(user);
        assertThat(me(access(logged))).isEqualTo(401);
        assertThat(restore(cookie).getResponse().getStatus()).isEqualTo(401);
        var relogged=login();
        user=users.findByUsername(username).orElseThrow(); user.setActive(false); users.saveAndFlush(user);
        assertThat(me(access(relogged))).isEqualTo(401);
        assertThat(restore(cookie(relogged)).getResponse().getStatus()).isEqualTo(401);
    }
    @Test void legacyJsonClientsKeepTheirContractAndHttpsCookiesAreSecure() throws Exception {
        var legacy=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username",username,"password",password)))).andReturn();
        String refresh=json.readTree(legacy.getResponse().getContentAsString()).path("refreshToken").asText();
        assertThat(refresh).isNotBlank();
        assertThat(legacy.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken",refresh)))).andReturn().getResponse().getStatus()).isEqualTo(200);
        var secure=mvc.perform(browser(post("/api/auth/login")).secure(true)
                .content(json.writeValueAsString(Map.of("username",username,"password",password)))).andReturn();
        assertThat(secure.getResponse().getHeader("Set-Cookie")).contains("Secure");
    }
    @Test void newBrowserLoginRevokesPreviousCookieFamily() throws Exception {
        var old=login();
        var next=mvc.perform(browser(post("/api/auth/login")).cookie(cookie(old))
                .content(json.writeValueAsString(Map.of("username",username,"password",password)))).andReturn();
        assertThat(next.getResponse().getStatus()).isEqualTo(200);
        assertThat(me(access(old))).isEqualTo(401);
        assertThat(me(access(next))).isEqualTo(200);
    }
}
