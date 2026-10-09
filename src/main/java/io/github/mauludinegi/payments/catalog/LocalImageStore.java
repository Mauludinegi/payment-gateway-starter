package io.github.mauludinegi.payments.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Images on local disk, served by {@link MediaController}. For local runs; a second instance would not see them. */
public class LocalImageStore implements ImageStore {

    private final Path root;

    public LocalImageStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes, ImageType type) {
        try {
            Path file = resolve(key);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new StorageException("Could not save the image", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String url(String key) {
        return "/api/media/" + key;
    }

    Optional<Path> file(String key) {
        Path file = resolve(key);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    private Path resolve(String key) {
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Invalid image key");
        }
        return file;
    }
}
