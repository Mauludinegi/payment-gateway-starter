package io.github.mauludinegi.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class PaymentsApplication {

    public static void main(String[] args) {
        try {
            String dbUrl = System.getenv("DATABASE_URL");
            String dbUser = System.getenv("DATABASE_USERNAME");
            String dbPass = System.getenv("DATABASE_PASSWORD");
            System.out.println("=== [DEBUG] SUPABASE DIAGNOSTIC START ===");
            System.out.println("[DEBUG] ENV DATABASE_URL: " + dbUrl);
            System.out.println("[DEBUG] ENV DATABASE_USERNAME: " + dbUser);
            System.out.println("[DEBUG] ENV DATABASE_PASSWORD present: " + (dbPass != null && !dbPass.isBlank()));

            String[] hosts = {"aws-0-ap-southeast-1.pooler.supabase.com"};
            int[] ports = {5432, 6543};
            for (String host : hosts) {
                for (int port : ports) {
                    try (java.net.Socket s = new java.net.Socket()) {
                        s.connect(new java.net.InetSocketAddress(host, port), 5000);
                        System.out.println("[DEBUG] TCP " + host + ":" + port + " -> CONNECTED SUCCESS");
                    } catch (Exception e) {
                        System.out.println("[DEBUG] TCP " + host + ":" + port + " -> FAILED: " + e.getMessage());
                    }
                }
            }

            if (dbUrl != null && !dbUrl.isBlank()) {
                try {
                    java.util.Properties props = new java.util.Properties();
                    if (dbUser != null) props.setProperty("user", dbUser);
                    if (dbPass != null) props.setProperty("password", dbPass);
                    props.setProperty("gssEncMode", "disable");
                    props.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
                    try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl, props)) {
                        System.out.println("[DEBUG] DIRECT JDBC TEST -> SUCCESSFUL CONNECTED TO DB!");
                    }
                } catch (Exception e) {
                    System.out.println("[DEBUG] DIRECT JDBC TEST -> FAILED: " + e.getMessage());
                }
            }
            System.out.println("=== [DEBUG] SUPABASE DIAGNOSTIC END ===");
        } catch (Throwable t) {
            System.out.println("[DEBUG] Diagnostic error: " + t.getMessage());
        }
        SpringApplication.run(PaymentsApplication.class, args);
    }
}
