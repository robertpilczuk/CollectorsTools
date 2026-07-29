package com.collectorstools.repository;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.ContactType;
import com.collectorstools.model.Interaction;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class InteractionRepository {

    private final Connection conn;

    public InteractionRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public InteractionRepository(Connection conn) {
        this.conn = conn;
    }

    public Interaction save(Interaction interaction) throws SQLException {
        String sql = """
            INSERT INTO interactions (debtor_id, interaction_date, contact_type, notes)
            VALUES (?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, interaction.getDebtorId());
            ps.setString(2, interaction.getInteractionDate().toString());
            ps.setString(3, interaction.getContactType().name());
            ps.setString(4, interaction.getNotes());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) interaction.setId(keys.getLong(1));
            }
        }
        return interaction;
    }

    public void update(Interaction interaction) throws SQLException {
        String sql = """
            UPDATE interactions SET interaction_date=?, contact_type=?, notes=?
            WHERE id=?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, interaction.getInteractionDate().toString());
            ps.setString(2, interaction.getContactType().name());
            ps.setString(3, interaction.getNotes());
            ps.setLong(4, interaction.getId());
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM interactions WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public List<Interaction> findByDebtorId(long debtorId) throws SQLException {
        List<Interaction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM interactions WHERE debtor_id=? ORDER BY interaction_date DESC")) {
            ps.setLong(1, debtorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public int countByDebtorId(long debtorId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM interactions WHERE debtor_id=?")) {
            ps.setLong(1, debtorId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    private Interaction map(ResultSet rs) throws SQLException {
        return Interaction.builder()
                .id(rs.getLong("id"))
                .debtorId(rs.getLong("debtor_id"))
                .interactionDate(LocalDate.parse(rs.getString("interaction_date")))
                .contactType(ContactType.valueOf(rs.getString("contact_type")))
                .notes(rs.getString("notes"))
                .build();
    }
}
