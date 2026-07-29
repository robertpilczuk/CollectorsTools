package com.collectorstools.service;

import com.collectorstools.model.AppUser;

/**
 * Użytkownik, w którego imieniu wykonywane są operacje.
 *
 * <p>Punkt, w którym w przyszłości podłączy się logowanie. Do tego czasu zwraca
 * jednego użytkownika lokalnego — dzięki temu wpisy audytowe mają autora od samego
 * początku i nie trzeba będzie uzupełniać ich wstecz.
 */
public final class CurrentUser {

    private static AppUser user = AppUser.local();

    private CurrentUser() {
    }

    public static AppUser get() {
        return user;
    }

    public static String id() {
        return user.id();
    }

    public static void set(AppUser newUser) {
        user = newUser != null ? newUser : AppUser.local();
    }
}
