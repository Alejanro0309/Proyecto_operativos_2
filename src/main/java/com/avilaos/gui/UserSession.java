package com.avilaos.gui;

import com.avilaos.enums.UserMode;

// Quién está usando la interfaz ahora: el modo (administrador o usuario) y, en modo usuario, con qué
// usuario. Los paneles lo consultan para impedir lo no permitido y explicar por qué.
public interface UserSession {

    UserMode getMode();

    String getUser();
}
