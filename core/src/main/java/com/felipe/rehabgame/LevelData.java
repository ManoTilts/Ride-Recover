package com.felipe.rehabgame;

import com.badlogic.gdx.math.Vector2;

/**
 * Represents a tile-based level loaded from a text file.
 * Each number represents a different tile type:
 * 0 = nothing (empty)
 * 1 = floor (grass.png)
 * 2 = ramp (ramp.png)
 * 3 = water (lake.png - obstacle)
 * 4 = flag (finish line)
 * 5 = player spawn point
 */
public class LevelData {
    public int[][] tiles;
    public int width;
    public int height;
    public Vector2 playerSpawn;
    public float tileSize;

    public LevelData(int width, int height, float tileSize) {
        this.width = width;
        this.height = height;
        this.tileSize = tileSize;
        this.tiles = new int[height][width];
        this.playerSpawn = new Vector2(0, 0);
    }

    public int getTile(int row, int col) {
        if (row < 0 || row >= height || col < 0 || col >= width) {
            return 0;
        }
        return tiles[row][col];
    }

    public void setTile(int row, int col, int tileType) {
        if (row >= 0 && row < height && col >= 0 && col < width) {
            tiles[row][col] = tileType;
        }
    }

    /**
     * Converts grid coordinates to world coordinates (grid space, not screen space).
     * Note: Y coordinate inversion for screen rendering happens in MainGame.
     */
    public Vector2 gridToWorld(int row, int col) {
        return new Vector2(col * tileSize, row * tileSize);
    }
}
