package io.github.mauludinegi.payments.catalog;

/** Stores product images under keys such as {@code products/<uuid>.webp} and tells where browsers can load them. */
public interface ImageStore {

    void put(String key, byte[] bytes, ImageType type);

    void delete(String key);

    String url(String key);

    class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
