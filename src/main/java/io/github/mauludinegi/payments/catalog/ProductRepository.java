package io.github.mauludinegi.payments.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findByActiveTrueOrderBySortOrder();
}
