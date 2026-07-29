package com.collectorstools.repository;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.Debt;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DebtRepository {

    private final Connection conn;

    public DebtRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public DebtRepository(Connection conn) {
        this.conn = conn;
    }

    public Debt save(Debt debt) throws SQLException {
        String sql = """
            INSERT INTO debts (debtor_id, case_number, creditor, total_amount, remaining_amount, debt_date, description)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, debt.getDebtorId());
            ps.setString(2, debt.getCaseNumber());
            ps.setString(3, debt.getCreditor());
            ps.setBigDecimal(4, debt.getTotalAmount());
            ps.setBigDecimal(5, debt.getRemainingAmount());
            ps.setString(6, debt.getDebtDate() != null ? debt.getDebtDate().toString() : null);
            ps.setString(7, debt.getDescription());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) debt.setId(keys.getLong(1));
            }
        }
        return debt;
    }

    public void update(Debt debt) throws SQLException {
        String sql = """
            UPDATE debts SET case_number=?, creditor=?, total_amount=?, remaining_amount=?,
                             debt_date=?, description=?
            WHERE id=?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, debt.getCaseNumber());
            ps.setString(2, debt.getCreditor());
            ps.setBigDecimal(3, debt.getTotalAmount());
            ps.setBigDecimal(4, debt.getRemainingAmount());
            ps.setString(5, debt.getDebtDate() != null ? debt.getDebtDate().toString() : null);
            ps.setString(6, debt.getDescription());
            ps.setLong(7, debt.getId());
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM debts WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Debt> findById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM debts WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public List<Debt> findByDebtorId(long debtorId) throws SQLException {
        List<Debt> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM debts WHERE debtor_id=? ORDER BY debt_date DESC")) {
            ps.setLong(1, debtorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public BigDecimal sumAllRemaining() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(remaining_amount), 0) FROM debts")) {
            return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
        }
    }

    public Set<Long> findDebtorIdsByCreditor(String phrase) throws SQLException {
        Set<Long> ids = new HashSet<>();
        String sql = "SELECT DISTINCT debtor_id FROM debts WHERE lower(creditor) LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + phrase.toLowerCase() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("debtor_id"));
            }
        }
        return ids;
    }

    private Debt map(ResultSet rs) throws SQLException {
        String dateStr = rs.getString("debt_date");
        return Debt.builder()
                .id(rs.getLong("id"))
                .debtorId(rs.getLong("debtor_id"))
                .caseNumber(rs.getString("case_number"))
                .creditor(rs.getString("creditor"))
                .totalAmount(rs.getBigDecimal("total_amount"))
                .remainingAmount(rs.getBigDecimal("remaining_amount"))
                .debtDate(dateStr != null ? LocalDate.parse(dateStr) : null)
                .description(rs.getString("description"))
                .build();
    }
}
