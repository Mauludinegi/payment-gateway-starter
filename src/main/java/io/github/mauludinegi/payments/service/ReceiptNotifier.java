package io.github.mauludinegi.payments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs after the PAID status is committed, on another thread, so slow email, WhatsApp, or analytics
 * calls never delay the webhook response or roll back a payment.
 */
@Component
public class ReceiptNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReceiptNotifier.class);

    @Async
    @TransactionalEventListener
    public void onOrderPaid(OrderPaidEvent event) {
        log.info("Order {} paid via {} (IDR {}): send the receipt and track the conversion here",
                event.reference(), event.channel(), event.amount());
    }
}
