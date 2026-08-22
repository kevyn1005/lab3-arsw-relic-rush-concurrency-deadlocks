package edu.eci.arsw.relicrush.gui;

public enum AdventurerState {
    WAITING_FOR_STATION,   // eligió estaciones, pero aún no las tiene (puede estar bloqueado)
    CRAFTING,              // YA tiene ambos locks, está craftando de verdad
    DONE_WAITING_BARRIER   // terminó, esperando a los demás
}