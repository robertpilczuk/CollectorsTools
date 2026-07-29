package com.collectorstools.repository;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.DebtorPhone;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DebtorPhoneRepository {

    private final Connection conn;

    public DebtorPhoneRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public DebtorPhoneRepository(Connection conn) {
        this.conn = conn;
    }

    public DebtorPhone save(DebtorPhone phone) throws SQLException {
        String sql = """
            INSERT INTO debtor_phones (debtor_id, phone_number, is_primary, source)
            VALUES (?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, phone.getDebtorId());
            ps.setString(2, phone.getPhoneNumber());
            ps.setInt(3, phone.isPrimary() ? 1 : 0);
            ps.setString(4, phone.getSource());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) phone.setId(keys.getLong(1));
            }
        }
        return phone;
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM debtor_phones WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void deleteByDebtorId(long debtorId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM debtor_phones WHERE debtor_id=?")) {
            ps.setLong(1, debtorId);
            ps.executeUpdate();
        }
    }

    /**
     * Numery dłużnika — podstawowy zawsze pierwszy.
     */
    public List<DebtorPhone> findByDebtorId(long debtorId) throws SQLException {
        String sql = "SELECT * FROM debtor_phones WHERE debtor_id=? ORDER BY is_primary DESC, id";
        List<DebtorPhone> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, debtorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    /**
     * Identyfikatory dłużników, których dowolny numer pasuje do frazy.
     * Porównanie pomija spacje i myślniki, bo eksporty zapisują numery niejednolicie.
     */
    public Set<Long> findDebtorIdsByPhone(String phrase) throws SQLException {
        String normalised = normalise(phrase);
        String sql = """
            SELECT DISTINCT debtor_id FROM debtor_phones
            WHERE replace(replace(replace(phone_number, ' ', ''), '-', ''), '+48', '') LIKE ?
        """;
        Set<Long> ids = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + normalised + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("debtor_id"));
            }
        }
        return ids;
    }

    public boolean exists(long debtorId, String phoneNumber) throws SQLException {
        String sql = "SELECT 1 FROM debtor_phones WHERE debtor_id=? AND phone_number=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, debtorId);
            ps.setString(2, phoneNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String normalise(String phone) {
        return phone == null ? "" : phone.replace(" ", "").replace("-", "").replace("+48", "");
    }

    private DebtorPhone map(ResultSet rs) throws SQLException {
        return DebtorPhone.builder()
                .id(rs.getLong("id"))
                .debtorId(rs.getLong("debtor_id"))
                .phoneNumber(rs.getString("phone_number"))
                .primary(rs.getInt("is_primary") == 1)
                .source(rs.getString("source"))
                .build();
    }
}
