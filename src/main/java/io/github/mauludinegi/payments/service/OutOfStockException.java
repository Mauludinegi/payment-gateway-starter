package io.github.mauludinegi.payments.service;

/** Mapped to 409 like other conflicts; the message tells the customer how many are left. */
public class OutOfStockException extends IllegalStateException {

    public OutOfStockException(String productName, int left) {
        super(left == 0 ? productName + " is sold out" : "Only " + left + " left of " + productName);
    }
}
