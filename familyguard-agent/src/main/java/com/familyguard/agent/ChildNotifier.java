package com.familyguard.agent;

/** Avisos visibles para el hijo (área de notificación de Windows). Nunca bloquea ni falla la supervisión. */
interface ChildNotifier {
    void notify(String text);
    ChildNotifier NONE = text -> { };
}
