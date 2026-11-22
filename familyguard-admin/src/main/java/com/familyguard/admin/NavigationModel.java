package com.familyguard.admin;

import java.util.Objects;

final class NavigationModel {
    private DashboardSection current = DashboardSection.HOME;

    DashboardSection current() { return current; }

    void navigate(DashboardSection section) {
        current = Objects.requireNonNull(section, "section");
    }
}
