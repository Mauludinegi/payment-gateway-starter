package io.github.mauludinegi.payments.auth;

public enum Role {
    /** Shops and sees their own orders. */
    CUSTOMER,
    /** Also uses the admin dashboard and manages roles. */
    ADMIN
}
