package io.github.mauludinegi.payments.config;

import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Provider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentsPropertiesTest {

    @Test
    void routesFromOneVariableOverrideTheMapAndTheDefault() {
        PaymentsProperties properties = properties(Map.of(Channel.DANA, Provider.MIDTRANS, Channel.GOPAY, Provider.MIDTRANS),
                " dana:xendit, BSI_VA:XENDIT,BSS_VA:XENDIT ");

        assertThat(properties.providerFor(Channel.DANA)).isEqualTo(Provider.XENDIT);
        assertThat(properties.providerFor(Channel.BSI_VA)).isEqualTo(Provider.XENDIT);
        assertThat(properties.providerFor(Channel.BSS_VA)).isEqualTo(Provider.XENDIT);
        assertThat(properties.providerFor(Channel.GOPAY)).isEqualTo(Provider.MIDTRANS);
        assertThat(properties.providerFor(Channel.BCA_VA)).isEqualTo(Provider.MIDTRANS);
    }

    @Test
    void blankRoutesKeepTheDefault() {
        assertThat(properties(null, "").providerFor(Channel.DANA)).isEqualTo(Provider.MIDTRANS);
    }

    @Test
    void typosFailAtStartup() {
        assertThatThrownBy(() -> properties(null, "DANA=XENDIT")).hasMessageContaining("CHANNEL:PROVIDER");
        assertThatThrownBy(() -> properties(null, "DANNA:XENDIT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesBindFromARealEnvironmentVariable() {
        SystemEnvironmentPropertySource env = new SystemEnvironmentPropertySource("env",
                Map.of("PAYMENTS_DEFAULT_PROVIDER", "MIDTRANS", "PAYMENTS_ROUTES", "DANA:XENDIT,BSS_VA:XENDIT"));

        PaymentsProperties properties = new Binder(ConfigurationPropertySources.from(env))
                .bind("payments", PaymentsProperties.class).get();

        assertThat(properties.providerFor(Channel.DANA)).isEqualTo(Provider.XENDIT);
        assertThat(properties.providerFor(Channel.BSS_VA)).isEqualTo(Provider.XENDIT);
        assertThat(properties.providerFor(Channel.BCA_VA)).isEqualTo(Provider.MIDTRANS);
    }

    private static PaymentsProperties properties(Map<Channel, Provider> routing, String routes) {
        return new PaymentsProperties(Provider.MIDTRANS, routing, null, null, null, null, null, null, routes);
    }

    @Test
    void knowsWhichCredentialsAreTestOnes() {
        PaymentsProperties test = new PaymentsProperties(null, null, null, null, null,
                new PaymentsProperties.Xendit("xnd_development_abc", null, null), new PaymentsProperties.Midtrans("Mid-server-x", null), null, null);
        assertThat(test.isTestMode(Provider.SIMULATOR)).isTrue();
        assertThat(test.isTestMode(Provider.XENDIT)).isTrue();
        assertThat(test.isTestMode(Provider.MIDTRANS)).isTrue();

        PaymentsProperties live = new PaymentsProperties(null, null, null, null, null,
                new PaymentsProperties.Xendit("xnd_production_abc", null, null),
                new PaymentsProperties.Midtrans("Mid-server-x", "https://api.midtrans.com"), null, null);
        assertThat(live.isTestMode(Provider.XENDIT)).isFalse();
        assertThat(live.isTestMode(Provider.MIDTRANS)).isFalse();
    }
}
