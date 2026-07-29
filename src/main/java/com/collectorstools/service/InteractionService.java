package com.collectorstools.service;

import com.collectorstools.model.Interaction;
import com.collectorstools.repository.InteractionRepository;

import java.sql.SQLException;
import java.util.List;

public class InteractionService {

    private final InteractionRepository interactionRepository;

    public InteractionService() {
        this.interactionRepository = new InteractionRepository();
    }

    public InteractionService(InteractionRepository interactionRepository) {
        this.interactionRepository = interactionRepository;
    }

    public Interaction save(Interaction interaction) {
        validate(interaction);
        try {
            return interactionRepository.save(interaction);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd zapisu interakcji: " + e.getMessage(), e);
        }
    }

    public void update(Interaction interaction) {
        validate(interaction);
        try {
            interactionRepository.update(interaction);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd aktualizacji interakcji: " + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try {
            interactionRepository.delete(id);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd usunięcia interakcji: " + e.getMessage(), e);
        }
    }

    public List<Interaction> findByDebtorId(long debtorId) {
        try {
            return interactionRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd pobierania interakcji: " + e.getMessage(), e);
        }
    }

    public int countByDebtorId(long debtorId) {
        try {
            return interactionRepository.countByDebtorId(debtorId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd liczenia interakcji: " + e.getMessage(), e);
        }
    }

    private void validate(Interaction interaction) {
        if (interaction.getDebtorId() == null)
            throw new IllegalArgumentException("Interakcja musi być przypisana do dłużnika.");
        if (interaction.getInteractionDate() == null)
            throw new IllegalArgumentException("Data interakcji jest wymagana.");
        if (interaction.getContactType() == null)
            throw new IllegalArgumentException("Typ kontaktu jest wymagany.");
    }
}
