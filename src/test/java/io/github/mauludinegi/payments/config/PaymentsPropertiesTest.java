package io.github.mauludinegi.payments.config;

import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Provider;
import org.junit.jupiter.api.Test;

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

    private static PaymentsProperties properties(Map<Channel, Provider> routing, String routes) {
        return new PaymentsProperties(Provider.MIDTRANS, routing, null, null, null, null, null, null, routes);
    }
}
