package edu.eci.arsw.relicrush.model;

public final class ForgeStation {
    private final int id;
    private final String name;

    private volatile int occupantId = 0;

    public ForgeStation(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setOccupant(int playerId) {
        this.occupantId = playerId;
    }

    public int occupantId() {
        return occupantId;
    }

    public boolean isOccupied() {
        return occupantId != 0;
    }

    @Override
    public String toString() {
        return name + "(#" + id + ")";
    }
}