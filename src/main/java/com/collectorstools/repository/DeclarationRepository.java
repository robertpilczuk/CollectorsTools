package com.collectorstools.repository;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.DeclarationStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DeclarationRepository {

    private static final Logger log = LoggerFactory.getLogger(DeclarationRepository.class);

    private final Connection conn;

    public DeclarationRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public DeclarationRepository(Connection conn) {
        this.conn = conn;
    }

    public Declaration save(Declaration declaration) throws SQLException {
        String sql = """
            INSERT INTO declarations (debtor_id, debt_id, promised_date, promised_amount, status,
                                      broken_reason, cancelled_reason, payment_date, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        if (declaration.getCreatedAt() == null) {
            declaration.setCreatedAt(LocalDate.now());
        }
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, declaration.getDebtorId());
            setNullableLong(ps, 2, declaration.getDebtId());
            ps.setString(3, declaration.getPromisedDate().toString());
            ps.setBigDecimal(4, declaration.getPromisedAmount());
            ps.setString(5, declaration.getStatus().name());
            ps.setString(6, declaration.getBrokenReason());
            ps.setString(7, declaration.getCancelledReason());
            ps.setString(8, declaration.getPaymentDate() != null ? declaration.getPaymentDate().toString() : null);
            ps.setString(9, declaration.getCreatedAt().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) declaration.setId(keys.getLong(1));
            }
        }
        return declaration;
    }

    /**
     * Update aktualizuje TYLKO pola statusu i dat — nie rusza debt_id
     * żeby nie naruszać foreign key constraint.
     *
     * <p>Nie zmienia też created_at: data rejestracji deklaracji jest faktem
     * historycznym i nie podlega edycji.
     */
    public void update(Declaration declaration) throws SQLException {
        String sql = """
            UPDATE declarations
            SET promised_date=?, promised_amount=?, status=?, broken_reason=?,
                cancelled_reason=?, payment_date=?
            WHERE id=?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, declaration.getPromisedDate().toString());
            ps.setBigDecimal(2, declaration.getPromisedAmount());
            ps.setString(3, declaration.getStatus().name());
            ps.setString(4, declaration.getBrokenReason());
            ps.setString(5, declaration.getCancelledReason());
            ps.setString(6, declaration.getPaymentDate() != null ? declaration.getPaymentDate().toString() : null);
            ps.setLong(7, declaration.getId());
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM declarations WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Declaration> findById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM declarations WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public List<Declaration> findByDebtorId(long debtorId) throws SQLException {
        List<Declaration> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM declarations WHERE debtor_id=? ORDER BY promised_date DESC")) {
            ps.setLong(1, debtorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public List<Declaration> findByDateRange(LocalDate from, LocalDate to) throws SQLException {
        List<Declaration> list = new ArrayList<>();
        String sql = "SELECT * FROM declarations WHERE promised_date BETWEEN ? AND ? ORDER BY promised_date";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    /**
     * Oznacza jako BROKEN przeterminowane deklaracje oczekujące. Wywoływać przy starcie aplikacji.
     *
     * <p>Warunek {@code promised_date < date('now')} jest ostry — deklaracja z terminem
     * na dziś pozostaje oczekująca przez cały dzień terminu.
     *
     * <p>Zmiana obejmuje wyłącznie status PENDING. Deklaracje częściowo opłacone
     * nie są łamane automatycznie: dłużnik już zapłacił część, więc o niedotrzymaniu
     * decyduje windykator. Anulowanych i rozliczonych operacja nie dotyka.
     */
    public void refreshOverdue() throws SQLException {
        String sql = """
            UPDATE declarations SET status='BROKEN'
            WHERE status='PENDING' AND promised_date < date('now')
        """;
        try (Statement st = conn.createStatement()) {
            int updated = st.executeUpdate(sql);
            if (updated > 0) {
                log.info("Oznaczono {} przeterminowanych deklaracji jako złamane.", updated);
            }
        }
    }

    public List<Declaration> findByStatus(DeclarationStatus status) throws SQLException {
        List<Declaration> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM declarations WHERE status=? ORDER BY promised_date")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public long countByStatus(DeclarationStatus status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM declarations WHERE status=?")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private Declaration map(ResultSet rs) throws SQLException {
        String promisedDateStr = rs.getString("promised_date");
        String paymentDateStr  = rs.getString("payment_date");
        String createdAtStr    = rs.getString("created_at");
        long debtId = rs.getLong("debt_id");
        boolean debtIdNull = rs.wasNull();

        return Declaration.builder()
                .id(rs.getLong("id"))
                .debtorId(rs.getLong("debtor_id"))
                .debtId(debtIdNull ? null : debtId)
                .createdAt(createdAtStr != null ? LocalDate.parse(createdAtStr) : null)
                .promisedDate(promisedDateStr != null ? LocalDate.parse(promisedDateStr) : null)
                .promisedAmount(rs.getBigDecimal("promised_amount"))
                .status(DeclarationStatus.valueOf(rs.getString("status")))
                .brokenReason(rs.getString("broken_reason"))
                .cancelledReason(rs.getString("cancelled_reason"))
                .paymentDate(paymentDateStr != null ? LocalDate.parse(paymentDateStr) : null)
                .build();
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) ps.setLong(index, value);
        else ps.setNull(index, Types.INTEGER);
    }
}
