package io.github.mauludinegi.payments.admin;

import io.github.mauludinegi.payments.auth.Role;
import io.github.mauludinegi.payments.auth.User;
import io.github.mauludinegi.payments.auth.UserRepository;
import io.github.mauludinegi.payments.order.OrderRepository;
import io.github.mauludinegi.payments.order.UserOrderTotal;
import io.github.mauludinegi.payments.service.NotFoundException;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final UserRepository users;
    private final OrderRepository orders;

    public UserAdminService(UserRepository users, OrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    public record UserRow(UUID id, String name, String email, String pictureUrl, Role role,
                          Instant createdAt, Instant lastLoginAt, long orders, long spent) {
    }

    @Transactional(readOnly = true)
    public AdminService.PageResult<UserRow> users(Role role, String q, int page, int size) {
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (role != null) {
                where.add(cb.equal(root.get("role"), role));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("email"), "")), like)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<User> result = users.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastLoginAt")));
        List<UUID> ids = result.getContent().stream().map(User::getId).toList();
        Map<UUID, UserOrderTotal> totals = ids.isEmpty() ? Map.of()
                : orders.totalsByUser(ids).stream().collect(Collectors.toMap(UserOrderTotal::userId, Function.identity()));
        List<UserRow> rows = result.getContent().stream().map(u -> {
            UserOrderTotal t = totals.get(u.getId());
            return row(u, t);
        }).toList();
        return new AdminService.PageResult<>(rows, page, size, result.getTotalElements());
    }

    /** Admins cannot change their own role, so the last admin can never lock everyone out. */
    @Transactional
    public UserRow changeRole(UUID actorId, UUID userId, Role role) {
        if (actorId.equals(userId)) {
            throw new IllegalStateException("You cannot change your own role");
        }
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
        if (user.getRole() != role) {
            log.info("User {} changed the role of {} from {} to {}", actorId, userId, user.getRole(), role);
            user.changeRole(role);
        }
        List<UserOrderTotal> totals = orders.totalsByUser(List.of(userId));
        return row(user, totals.isEmpty() ? null : totals.getFirst());
    }

    private static UserRow row(User u, UserOrderTotal t) {
        return new UserRow(u.getId(), u.getName(), u.getEmail(), u.getPictureUrl(), u.getRole(), u.getCreatedAt(),
                u.getLastLoginAt(), t == null ? 0 : t.orders(), t == null ? 0 : t.spent());
    }
}
