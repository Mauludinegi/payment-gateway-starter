package io.github.mauludinegi.payments.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findByActiveTrueOrderBySortOrder();

    List<Product> findAllByOrderBySortOrderAscNameAsc();

    /** Takes stock only if enough is left, in one statement, so two buyers can never both get the last one. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :quantity, p.version = p.version + 1 "
            + "where p.id = :id and p.stock is not null and p.stock >= :quantity")
    int takeStock(@Param("id") String id, @Param("quantity") int quantity);

    @Query("select coalesce(p.stock, 0) from Product p where p.id = :id")
    int stockOf(@Param("id") String id);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :quantity, p.version = p.version + 1 "
            + "where p.id = :id and p.stock is not null")
    int returnStock(@Param("id") String id, @Param("quantity") int quantity);
}
