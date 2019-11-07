/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.dse.driver.api.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.datastax.dse.driver.api.core.DseSession;
import com.datastax.dse.driver.api.core.config.DseDriverOption;
import com.datastax.dse.driver.internal.core.auth.DseGssApiAuthProvider;
import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.auth.AuthenticationException;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.servererrors.UnauthorizedException;
import com.datastax.oss.driver.api.testinfra.DseRequirement;
import com.datastax.oss.driver.api.testinfra.session.SessionUtils;
import com.datastax.oss.driver.internal.core.auth.PlainTextAuthProvider;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

@DseRequirement(min = "5.1", description = "Required for DseAuthenticator with proxy")
public class DseProxyAuthenticationIT {
  private static String bobPrincipal;
  private static String charliePrincipal;
  @ClassRule public static EmbeddedAdsRule ads = new EmbeddedAdsRule();

  @BeforeClass
  public static void addUsers() {
    bobPrincipal = ads.addUserAndCreateKeyTab("bob", "bob");
    charliePrincipal = ads.addUserAndCreateKeyTab("charlie", "charlie");
  }

  @Before
  public void setupRoles() {

    try (DseSession session = ads.newKeyTabSession()) {
      session.execute("CREATE ROLE IF NOT EXISTS alice WITH PASSWORD = 'alice' AND LOGIN = FALSE");
      session.execute("CREATE ROLE IF NOT EXISTS ben WITH PASSWORD = 'ben' AND LOGIN = TRUE");
      session.execute("CREATE ROLE IF NOT EXISTS 'bob@DATASTAX.COM' WITH LOGIN = TRUE");
      session.execute(
          "CREATE ROLE IF NOT EXISTS 'charlie@DATASTAX.COM' WITH PASSWORD = 'charlie' AND LOGIN = TRUE");
      session.execute("CREATE ROLE IF NOT EXISTS steve WITH PASSWORD = 'steve' AND LOGIN = TRUE");
      session.execute(
          "CREATE KEYSPACE IF NOT EXISTS aliceks WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':'1'}");
      session.execute(
          "CREATE TABLE IF NOT EXISTS aliceks.alicetable (key text PRIMARY KEY, value text)");
      session.execute("INSERT INTO aliceks.alicetable (key, value) VALUES ('hello', 'world')");
      session.execute("GRANT ALL ON KEYSPACE aliceks TO alice");
      session.execute("GRANT EXECUTE ON ALL AUTHENTICATION SCHEMES TO 'ben'");
      session.execute("GRANT EXECUTE ON ALL AUTHENTICATION SCHEMES TO 'bob@DATASTAX.COM'");
      session.execute("GRANT EXECUTE ON ALL AUTHENTICATION SCHEMES TO 'steve'");
      session.execute("GRANT EXECUTE ON ALL AUTHENTICATION SCHEMES TO 'charlie@DATASTAX.COM'");
      session.execute("GRANT PROXY.LOGIN ON ROLE 'alice' TO 'ben'");
      session.execute("GRANT PROXY.LOGIN ON ROLE 'alice' TO 'bob@DATASTAX.COM'");
      session.execute("GRANT PROXY.EXECUTE ON ROLE 'alice' TO 'steve'");
      session.execute("GRANT PROXY.EXECUTE ON ROLE 'alice' TO 'charlie@DATASTAX.COM'");
      // ben and bob are allowed to login as alice, but not execute as alice.
      // charlie and steve are allowed to execute as alice, but not login as alice.
    }
  }
  /**
   * Validates that a connection may be successfully made as user 'alice' using the credentials of a
   * user 'ben' using {@link PlainTextAuthProvider} assuming ben has PROXY.LOGIN authorization on
   * alice.
   */
  @Test
  public void should_allow_plain_text_authorized_user_to_login_as() {
    try (DseSession session =
        SessionUtils.newSession(
            ads.ccm,
            SessionUtils.configLoaderBuilder()
                .withString(DseDriverOption.AUTH_PROVIDER_AUTHORIZATION_ID, "alice")
                .withString(DefaultDriverOption.AUTH_PROVIDER_USER_NAME, "ben")
                .withString(DefaultDriverOption.AUTH_PROVIDER_PASSWORD, "ben")
                .withClass(DefaultDriverOption.AUTH_PROVIDER_CLASS, PlainTextAuthProvider.class)
                .build())) {
      SimpleStatement select = SimpleStatement.builder("select * from aliceks.alicetable").build();
      ResultSet set = session.execute(select);
      assertThat(set).isNotNull();
    }
  }

  @Test
  public void should_allow_plain_text_authorized_user_to_login_as_programmatically() {
    try (DseSession session =
        DseSession.builder()
            .addContactEndPoints(ads.ccm.getContactPoints())
            .withAuthCredentials("ben", "ben", "alice")
            .build()) {
      session.execute("select * from system.local");
    }
  }

  /**
   * Validates that a connection may successfully made as user 'alice' using the credentials of a
   * principal 'bob@DATASTAX.COM' using {@link DseGssApiAuthProvider} assuming 'bob@DATASTAX.COM'
   * has PROXY.LOGIN authorization on alice.
   */
  @Test
  public void should_allow_kerberos_authorized_user_to_login_as() {
    try (DseSession session =
        ads.newKeyTabSession(
            bobPrincipal, ads.getKeytabForPrincipal(bobPrincipal).getAbsolutePath(), "alice")) {
      SimpleStatement select = SimpleStatement.builder("select * from aliceks.alicetable").build();
      ResultSet set = session.execute(select);
      assertThat(set).isNotNull();
    }
  }

  /**
   * Validates that a connection does not succeed as user 'alice' using the credentials of a user
   * 'steve' assuming 'steve' does not have PROXY.LOGIN authorization on alice.
   */
  @Test
  public void should_not_allow_plain_text_unauthorized_user_to_login_as() {
    try (DseSession session =
        SessionUtils.newSession(
            ads.ccm,
            SessionUtils.configLoaderBuilder()
                .withString(DseDriverOption.AUTH_PROVIDER_AUTHORIZATION_ID, "alice")
                .withString(DefaultDriverOption.AUTH_PROVIDER_USER_NAME, "steve")
                .withString(DefaultDriverOption.AUTH_PROVIDER_PASSWORD, "steve")
                .withClass(DefaultDriverOption.AUTH_PROVIDER_CLASS, PlainTextAuthProvider.class)
                .build())) {
      SimpleStatement select = SimpleStatement.builder("select * from aliceks.alicetable").build();
      session.execute(select);
      fail("Should have thrown AllNodesFailedException on login.");
    } catch (AllNodesFailedException anfe) {
      verifyException(anfe);
    }
  }
  /**
   * Validates that a connection does not succeed as user 'alice' using the credentials of a
   * principal 'charlie@DATASTAX.COM' assuming 'charlie@DATASTAX.COM' does not have PROXY.LOGIN
   * authorization on alice.
   */
  @Test
  public void should_not_allow_kerberos_unauthorized_user_to_login_as() throws Exception {
    try (DseSession session =
        ads.newKeyTabSession(
            charliePrincipal,
            ads.getKeytabForPrincipal(charliePrincipal).getAbsolutePath(),
            "alice")) {
      SimpleStatement select = SimpleStatement.builder("select * from aliceks.alicetable").build();
      session.execute(select);
      fail("Should have thrown AllNodesFailedException on login.");
    } catch (AllNodesFailedException anfe) {
      verifyException(anfe);
    }
  }
  /**
   * Validates that a query may be successfully made as user 'alice' using a {@link DseSession} that
   * is authenticated to user 'steve' using {@link PlainTextAuthProvider} assuming steve has
   * PROXY.EXECUTE authorization on alice.
   */
  @Test
  public void should_allow_plain_text_authorized_user_to_execute_as() {
    try (DseSession session =
        SessionUtils.newSession(
            ads.ccm,
            SessionUtils.configLoaderBuilder()
                .withString(DefaultDriverOption.AUTH_PROVIDER_USER_NAME, "steve")
                .withString(DefaultDriverOption.AUTH_PROVIDER_PASSWORD, "steve")
                .withClass(DefaultDriverOption.AUTH_PROVIDER_CLASS, PlainTextAuthProvider.class)
                .build())) {
      SimpleStatement select = SimpleStatement.builder("select * from aliceks.alicetable").build();
      SimpleStatement statementAsAlice = ProxyAuthentication.executeAs("alice", select);
      ResultSet set = session.execute(statementAsAlice);
      assertThat(set).isNotNull();
    }
  }
  /**
   * Validates that a query may be successfully made as user 'alice' using a {@link DseSession} that
   * is authenticated to principal 'charlie@DATASTAX.COM' using {@link DseGssApiAuthProvider}
   * assuming charlie@DATASTAX.COM has PROXY.EXECUTE authorization on alice.
   */
  @Test
  public void should_allow_kerberos_authorized_user_to_execute_as() {
    try (DseSession session =
        ads.newKeyTabSession(
            charliePrincipal, ads.getKeytabForPrincipal(charliePrincipal).getAbsolutePath())) {
      SimpleStatement select = SimpleStatement.builder("select * from aliceks.alicetable").build();
      SimpleStatement statementAsAlice = ProxyAuthentication.executeAs("alice", select);
      ResultSet set = session.execute(statementAsAlice);
      assertThat(set).isNotNull();
    }
  }
  /**
   * Validates that a query may not be made as user 'alice' using a {@link DseSession} that is
   * authenticated to user 'ben' if ben does not have PROXY.EXECUTE authorization on alice.
   */
  @Test
  public void should_not_allow_plain_text_unauthorized_user_to_execute_as() {
    try (DseSession session =
        SessionUtils.newSession(
            ads.ccm,
            SessionUtils.configLoaderBuilder()
                .withString(DefaultDriverOption.AUTH_PROVIDER_USER_NAME, "ben")
                .withString(DefaultDriverOption.AUTH_PROVIDER_PASSWORD, "ben")
                .withClass(DefaultDriverOption.AUTH_PROVIDER_CLASS, PlainTextAuthProvider.class)
                .build())) {
      SimpleStatement select = SimpleStatement.builder("select * from aliceks.alicetable").build();
      SimpleStatement statementAsAlice = ProxyAuthentication.executeAs("alice", select);
      session.execute(statementAsAlice);
      fail("Should have thrown UnauthorizedException on executeAs.");
    } catch (UnauthorizedException ue) {
      verifyException(ue, "ben");
    }
  }
  /**
   * Validates that a query may not be made as user 'alice' using a {@link DseSession} that is
   * authenticated to principal 'bob@DATASTAX.COM' using {@link DseGssApiAuthProvider} if
   * bob@DATASTAX.COM does not have PROXY.EXECUTE authorization on alice.
   */
  @Test
  public void should_not_allow_kerberos_unauthorized_user_to_execute_as() {
    try (DseSession session =
        ads.newKeyTabSession(
            bobPrincipal, ads.getKeytabForPrincipal(bobPrincipal).getAbsolutePath())) {
      SimpleStatement select = SimpleStatement.builder("select * from aliceks.alicetable").build();
      SimpleStatement statementAsAlice = ProxyAuthentication.executeAs("alice", select);
      session.execute(statementAsAlice);
      fail("Should have thrown UnauthorizedException on executeAs.");
    } catch (UnauthorizedException ue) {
      verifyException(ue, "bob@DATASTAX.COM");
    }
  }

  private void verifyException(AllNodesFailedException anfe) {
    Throwable firstError = anfe.getErrors().values().iterator().next();
    assertThat(firstError).isInstanceOf(AuthenticationException.class);
    assertThat(firstError.getMessage())
        .contains(
            "Authentication error on node /127.0.0.1:9042: server replied 'Failed to login. Please re-try.'");
  }

  private void verifyException(UnauthorizedException ue, String user) {
    assertThat(ue.getMessage())
        .contains(
            String.format(
                "Either '%s' does not have permission to execute queries as 'alice' "
                    + "or that role does not exist.",
                user));
  }
}
