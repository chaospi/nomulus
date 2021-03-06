// Copyright 2018 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableList;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DatastoreHelper;
import google.registry.util.CidrAddressBlock;
import google.registry.util.SystemClock;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class OteAccountBuilderTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Test
  public void testGetRegistrarToTldMap() {
    assertThat(OteAccountBuilder.forClientId("myclientid").getClientIdToTldMap())
        .containsExactly(
            "myclientid-1", "myclientid-sunrise",
            "myclientid-2", "myclientid-landrush",
            "myclientid-3", "myclientid-ga",
            "myclientid-4", "myclientid-ga",
            "myclientid-5", "myclientid-eap");
  }

  @Before
  public void setUp() {
    persistPremiumList("default_sandbox_list", "sandbox,USD 1000");
  }

  private void assertTldExists(String tld, Registry.TldState tldState) {
    Registry registry = Registry.get(tld);
    assertThat(registry).isNotNull();
    assertThat(registry.getPremiumList().getName()).isEqualTo("default_sandbox_list");
    assertThat(registry.getTldStateTransitions()).containsExactly(START_OF_TIME, tldState);
    assertThat(registry.getDnsWriters()).containsExactly("VoidDnsWriter");
    assertThat(registry.getAddGracePeriodLength()).isEqualTo(Duration.standardDays(5));
    assertThat(registry.getPendingDeleteLength()).isEqualTo(Duration.standardDays(5));
    assertThat(registry.getRedemptionGracePeriodLength()).isEqualTo(Duration.standardDays(30));
    assertThat(registry.getEapFeeScheduleAsMap()).containsExactly(START_OF_TIME, Money.of(USD, 0));
  }

  private void assertTldExistsGa(String tld, Money eapFee) {
    Registry registry = Registry.get(tld);
    assertThat(registry).isNotNull();
    assertThat(registry.getPremiumList().getName()).isEqualTo("default_sandbox_list");
    assertThat(registry.getTldStateTransitions())
        .containsExactly(START_OF_TIME, TldState.GENERAL_AVAILABILITY);
    assertThat(registry.getDnsWriters()).containsExactly("VoidDnsWriter");
    assertThat(registry.getAddGracePeriodLength()).isEqualTo(Duration.standardHours(1));
    assertThat(registry.getPendingDeleteLength()).isEqualTo(Duration.standardMinutes(5));
    assertThat(registry.getRedemptionGracePeriodLength()).isEqualTo(Duration.standardMinutes(10));
    assertThat(registry.getCurrency()).isEqualTo(eapFee.getCurrencyUnit());
    // This uses "now" on purpose - so the test will break at 2022 when the current EapFee in OTE
    // goes back to 0
    assertThat(registry.getEapFeeFor(DateTime.now(DateTimeZone.UTC)).getCost())
        .isEqualTo(eapFee.getAmount());
  }

  private void assertRegistrarExists(String clientId, String tld) {
    Registrar registrar = Registrar.loadByClientId(clientId).orElse(null);
    assertThat(registrar).isNotNull();
    assertThat(registrar.getType()).isEqualTo(Registrar.Type.OTE);
    assertThat(registrar.getState()).isEqualTo(Registrar.State.ACTIVE);
    assertThat(registrar.getAllowedTlds()).containsExactly(tld);
  }

  private void assertContactExists(String clientId, String email) {
    Registrar registrar = Registrar.loadByClientId(clientId).get();
    assertThat(registrar.getContacts().stream().map(RegistrarContact::getEmailAddress))
        .contains(email);
    RegistrarContact contact =
        registrar.getContacts().stream()
            .filter(c -> email.equals(c.getEmailAddress()))
            .findAny()
            .get();
    assertThat(contact.getEmailAddress()).isEqualTo(email);
    assertThat(contact.getGaeUserId()).isNotEmpty();
  }

  @Test
  public void testCreateOteEntities_success() {
    OteAccountBuilder.forClientId("myclientid").addContact("email@example.com").buildAndPersist();

    assertTldExists("myclientid-sunrise", TldState.START_DATE_SUNRISE);
    assertTldExists("myclientid-landrush", TldState.LANDRUSH);
    assertTldExistsGa("myclientid-ga", Money.of(USD, 0));
    assertTldExistsGa("myclientid-eap", Money.of(USD, 100));
    assertRegistrarExists("myclientid-1", "myclientid-sunrise");
    assertRegistrarExists("myclientid-2", "myclientid-landrush");
    assertRegistrarExists("myclientid-3", "myclientid-ga");
    assertRegistrarExists("myclientid-4", "myclientid-ga");
    assertRegistrarExists("myclientid-5", "myclientid-eap");
    assertContactExists("myclientid-1", "email@example.com");
    assertContactExists("myclientid-2", "email@example.com");
    assertContactExists("myclientid-3", "email@example.com");
    assertContactExists("myclientid-4", "email@example.com");
    assertContactExists("myclientid-5", "email@example.com");
  }

  @Test
  public void testCreateOteEntities_multipleContacts_success() {
    OteAccountBuilder.forClientId("myclientid")
        .addContact("email@example.com")
        .addContact("other@example.com")
        .addContact("someone@example.com")
        .buildAndPersist();

    assertTldExists("myclientid-sunrise", TldState.START_DATE_SUNRISE);
    assertTldExists("myclientid-landrush", TldState.LANDRUSH);
    assertTldExistsGa("myclientid-ga", Money.of(USD, 0));
    assertTldExistsGa("myclientid-eap", Money.of(USD, 100));
    assertRegistrarExists("myclientid-1", "myclientid-sunrise");
    assertRegistrarExists("myclientid-2", "myclientid-landrush");
    assertRegistrarExists("myclientid-3", "myclientid-ga");
    assertRegistrarExists("myclientid-4", "myclientid-ga");
    assertRegistrarExists("myclientid-5", "myclientid-eap");
    assertContactExists("myclientid-1", "email@example.com");
    assertContactExists("myclientid-2", "email@example.com");
    assertContactExists("myclientid-3", "email@example.com");
    assertContactExists("myclientid-4", "email@example.com");
    assertContactExists("myclientid-5", "email@example.com");
    assertContactExists("myclientid-1", "other@example.com");
    assertContactExists("myclientid-2", "other@example.com");
    assertContactExists("myclientid-3", "other@example.com");
    assertContactExists("myclientid-4", "other@example.com");
    assertContactExists("myclientid-5", "other@example.com");
    assertContactExists("myclientid-1", "someone@example.com");
    assertContactExists("myclientid-2", "someone@example.com");
    assertContactExists("myclientid-3", "someone@example.com");
    assertContactExists("myclientid-4", "someone@example.com");
    assertContactExists("myclientid-5", "someone@example.com");
  }

  @Test
  public void testCreateOteEntities_setPassword() {
    OteAccountBuilder.forClientId("myclientid").setPassword("myPassword").buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().testPassword("myPassword")).isTrue();
  }

  @Test
  public void testCreateOteEntities_setCertificateHash() {
    OteAccountBuilder.forClientId("myclientid")
        .setCertificateHash(SAMPLE_CERT_HASH)
        .buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().getClientCertificateHash())
        .isEqualTo(SAMPLE_CERT_HASH);
  }

  @Test
  public void testCreateOteEntities_setCertificate() {
    OteAccountBuilder.forClientId("myclientid")
        .setCertificate(SAMPLE_CERT, new SystemClock().nowUtc())
        .buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().getClientCertificateHash())
        .isEqualTo(SAMPLE_CERT_HASH);
    assertThat(Registrar.loadByClientId("myclientid-3").get().getClientCertificate())
        .isEqualTo(SAMPLE_CERT);
  }

  @Test
  public void testCreateOteEntities_setIpWhitelist() {
    OteAccountBuilder.forClientId("myclientid")
        .setIpWhitelist(ImmutableList.of("1.1.1.0/24"))
        .buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().getIpAddressWhitelist())
        .containsExactly(CidrAddressBlock.create("1.1.1.0/24"));
  }

  @Test
  public void testCreateOteEntities_invalidClientId_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> OteAccountBuilder.forClientId("3blobio")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: 3blobio");
  }

  @Test
  public void testCreateOteEntities_clientIdTooShort_fails() {
    assertThat(
            assertThrows(IllegalArgumentException.class, () -> OteAccountBuilder.forClientId("bl")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: bl");
  }

  @Test
  public void testCreateOteEntities_clientIdTooLong_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> OteAccountBuilder.forClientId("blobiotoooolong")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: blobiotoooolong");
  }

  @Test
  public void testCreateOteEntities_clientIdBadCharacter_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> OteAccountBuilder.forClientId("blo#bio")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: blo#bio");
  }

  @Test
  public void testCreateOteEntities_entityExists_failsWhenNotReplaceExisting() {
    DatastoreHelper.persistSimpleResource(
        AppEngineRule.makeRegistrar1().asBuilder().setClientId("myclientid-1").build());
    OteAccountBuilder oteSetupHelper = OteAccountBuilder.forClientId("myclientid");

    assertThat(assertThrows(IllegalStateException.class, () -> oteSetupHelper.buildAndPersist()))
        .hasMessageThat()
        .contains("Found existing object(s) conflicting with OT&E objects");
  }

  @Test
  public void testCreateOteEntities_entityExists_succeedsWhenReplaceExisting() {
    DatastoreHelper.persistSimpleResource(
        AppEngineRule.makeRegistrar1().asBuilder().setClientId("myclientid-1").build());
    DatastoreHelper.createTld("myclientid-landrush", Registry.TldState.SUNRUSH);

    OteAccountBuilder.forClientId("myclientid").setReplaceExisting(true).buildAndPersist();

    assertTldExists("myclientid-landrush", TldState.LANDRUSH);
    assertRegistrarExists("myclientid-3", "myclientid-ga");
  }

  @Test
  public void testCreateOteEntities_doubleCreation_actuallyReplaces() {
    OteAccountBuilder.forClientId("myclientid")
        .setPassword("oldPassword")
        .addContact("email@example.com")
        .buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().testPassword("oldPassword")).isTrue();

    OteAccountBuilder.forClientId("myclientid")
        .setPassword("newPassword")
        .addContact("email@example.com")
        .setReplaceExisting(true)
        .buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().testPassword("oldPassword"))
        .isFalse();
    assertThat(Registrar.loadByClientId("myclientid-3").get().testPassword("newPassword")).isTrue();
  }

  @Test
  public void testCreateOteEntities_doubleCreation_keepsOldContacts() {
    OteAccountBuilder.forClientId("myclientid").addContact("email@example.com").buildAndPersist();

    assertContactExists("myclientid-3", "email@example.com");

    OteAccountBuilder.forClientId("myclientid")
        .addContact("other@example.com")
        .setReplaceExisting(true)
        .buildAndPersist();

    assertContactExists("myclientid-3", "other@example.com");
    assertContactExists("myclientid-3", "email@example.com");
  }
}
