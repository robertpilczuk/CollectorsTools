package com.collectorstools.repository;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.Payment;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PaymentRepository {

    private final Connection conn;

    public PaymentRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public PaymentRepository(Connection conn) {
        this.conn = conn;
    }

    public Payment save(Payment payment) throws SQLException {
        String sql = """
            INSERT INTO payments (debt_id, declaration_id, amount, payment_date,
                                  allocated_cost, allocated_interest, allocated_principal,
                                  note, source, created_at, user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, payment.getDebtId());
            setNullableLong(ps, 2, payment.getDeclarationId());
            ps.setBigDecimal(3, payment.getAmount());
            ps.setString(4, payment.getPaymentDate().toString());
            ps.setBigDecimal(5, zeroIfNull(payment.getAllocatedCost()));
            ps.setBigDecimal(6, zeroIfNull(payment.getAllocatedInterest()));
            ps.setBigDecimal(7, zeroIfNull(payment.getAllocatedPrincipal()));
            ps.setString(8, payment.getNote());
            ps.setString(9, payment.getSource() != null ? payment.getSource() : Payment.SOURCE_MANUAL);
            ps.setString(10, (payment.getCreatedAt() != null ? payment.getCreatedAt() : LocalDate.now()).toString());
            ps.setString(11, payment.getUserId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) payment.setId(keys.getLong(1));
            }
        }
        return payment;
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payments WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Payment> findById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM payments WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public List<Payment> findByDebtId(long debtId) throws SQLException {
        return query("SELECT * FROM payments WHERE debt_id=? ORDER BY payment_date DESC, id DESC", debtId);
    }

    public List<Payment> findByDeclarationId(long declarationId) throws SQLException {
        return query("SELECT * FROM payments WHERE declaration_id=? ORDER BY payment_date, id", declarationId);
    }

    /**
     * Wszystkie wpłaty dłużnika — z każdej jego sprawy, najnowsze pierwsze.
     */
    public List<Payment> findByDebtorId(long debtorId) throws SQLException {
        String sql = """
            SELECT p.* FROM payments p
            JOIN debts d ON d.id = p.debt_id
            WHERE d.debtor_id = ?
            ORDER BY p.payment_date DESC, p.id DESC
        """;
        return query(sql, debtorId);
    }

    public BigDecimal sumByDeclarationId(long declarationId) throws SQLException {
        return sum("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE declaration_id=?", declarationId);
    }

    public BigDecimal sumByDebtId(long debtId) throws SQLException {
        return sum("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE debt_id=?", debtId);
    }

    public BigDecimal sumByDebtorId(long debtorId) throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(p.amount), 0) FROM payments p
            JOIN debts d ON d.id = p.debt_id
            WHERE d.debtor_id = ?
        """;
        return sum(sql, debtorId);
    }

    public int countByDebtId(long debtId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM payments WHERE debt_id=?")) {
            ps.setLong(1, debtId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public BigDecimal sumAll() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(amount), 0) FROM payments")) {
            return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
        }
    }

    // ===== pomocnicze =====

    private List<Payment> query(String sql, long parameter) throws SQLException {
        List<Payment> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, parameter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    private BigDecimal sum(String sql, long parameter) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, parameter);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    private Payment map(ResultSet rs) throws SQLException {
        long declarationId = rs.getLong("declaration_id");
        boolean declarationNull = rs.wasNull();
        String createdAt = rs.getString("created_at");

        return Payment.builder()
                .id(rs.getLong("id"))
                .debtId(rs.getLong("debt_id"))
                .declarationId(declarationNull ? null : declarationId)
                .amount(rs.getBigDecimal("amount"))
                .paymentDate(LocalDate.parse(rs.getString("payment_date")))
                .allocatedCost(rs.getBigDecimal("allocated_cost"))
                .allocatedInterest(rs.getBigDecimal("allocated_interest"))
                .allocatedPrincipal(rs.getBigDecimal("allocated_principal"))
                .note(rs.getString("note"))
                .source(rs.getString("source"))
                .createdAt(createdAt != null ? LocalDate.parse(createdAt) : null)
                .userId(rs.getString("user_id"))
                .build();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) ps.setLong(index, value);
        else ps.setNull(index, Types.INTEGER);
    }
}
