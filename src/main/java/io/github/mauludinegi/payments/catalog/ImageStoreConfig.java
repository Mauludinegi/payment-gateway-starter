package io.github.mauludinegi.payments.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ImageStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(ImageStoreConfig.class);

    @Bean
    ImageStore imageStore(StorageProperties properties, RestClient.Builder builder) {
        if (properties.supabase().configured()) {
            log.info("Product images are stored in Supabase Storage bucket {}", properties.supabase().bucket());
            return new SupabaseImageStore(builder, properties.supabase());
        }
        log.info("SUPABASE_URL is not set; product images are stored in {}", properties.localDir().toAbsolutePath());
        return new LocalImageStore(properties.localDir());
    }
}
