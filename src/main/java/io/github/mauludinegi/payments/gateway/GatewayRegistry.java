package io.github.mauludinegi.payments.gateway;

import io.github.mauludinegi.payments.config.PaymentsProperties;
import io.github.mauludinegi.payments.payment.Channel;
import io.github.mauludinegi.payments.payment.Provider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class GatewayRegistry {

    private final Map<Provider, PaymentGateway> gateways = new EnumMap<>(Provider.class);
    private final PaymentsProperties properties;

    public GatewayRegistry(List<PaymentGateway> gateways, PaymentsProperties properties) {
        gateways.forEach(g -> this.gateways.put(g.provider(), g));
        this.properties = properties;
    }

    public PaymentGateway get(Provider provider) {
        PaymentGateway gateway = gateways.get(provider);
        if (gateway == null || !gateway.isConfigured()) {
            throw new GatewayNotConfiguredException(provider);
        }
        return gateway;
    }

    public PaymentGateway forChannel(Channel channel) {
        PaymentGateway gateway = get(properties.providerFor(channel));
        if (!gateway.channels().contains(channel)) {
            throw new IllegalArgumentException(channel.label() + " is not supported by " + gateway.provider());
        }
        return gateway;
    }

    /** Channels the checkout can offer right now, with the gateway each one is routed to. */
    public Map<Channel, Provider> availableChannels() {
        Map<Channel, Provider> available = new EnumMap<>(Channel.class);
        for (Channel channel : Channel.values()) {
            PaymentGateway gateway = gateways.get(properties.providerFor(channel));
            if (gateway != null && gateway.isConfigured() && gateway.channels().contains(channel)) {
                available.put(channel, gateway.provider());
            }
        }
        return available;
    }

    public static class GatewayNotConfiguredException extends RuntimeException {
        public GatewayNotConfiguredException(Provider provider) {
            super(provider + " is not configured");
        }
    }
}
