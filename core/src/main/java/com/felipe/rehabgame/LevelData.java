package com.felipe.rehabgame;

import com.badlogic.gdx.math.Vector2;

/**
 * Representa um nível baseado em tiles carregado a partir de um arquivo de texto.
 * Cada número representa um tipo diferente de tile:
 * 0 = nada (vazio)
 * 1 = chão (grass.png)
 * 2 = rampa (ramp.png)
 * 3 = água (lake.png – obstáculo)
 * 4 = bandeira (linha de chegada)
 * 5 = ponto de spawn do jogador
 */
public class LevelData {
    public int[][] tiles;
    public int width;
    public int height;
    public Vector2 playerSpawn;
    public float tileSize;
    public float timeLimit; // Limite de tempo em segundos (0 = sem limite)

    public LevelData(int width, int height, float tileSize) {
        this.width = width;
        this.height = height;
        this.tileSize = tileSize;
        this.tiles = new int[height][width];
        this.playerSpawn = new Vector2(0, 0);
        this.timeLimit = 0f; // Padrão: sem limite de tempo
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
     * Converte coordenadas da grade para coordenadas do mundo (espaço da grade, não da tela).
     * Observação: a inversão do eixo Y para renderização na tela acontece em MainGame.
     */
    public Vector2 gridToWorld(int row, int col) {
        return new Vector2(col * tileSize, row * tileSize);
    }
}
