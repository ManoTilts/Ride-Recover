package com.felipe.rehabgame;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads level data from text files in the assets folder.
 */
public class LevelLoader {
    
    public static LevelData loadLevel(String filename, float tileSize) {
        FileHandle file = Gdx.files.internal(filename);
        
        if (!file.exists()) {
            System.err.println("Level file not found: " + filename);
            return createEmptyLevel(10, 10, tileSize);
        }

        String content = file.readString();
        String[] lines = content.split("\n");
        
        float timeLimit = 0f; // Default: no time limit
        
        List<String> validLines = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                // Check for time parameter (format: "time: 60")
                if (trimmed.toLowerCase().startsWith("time:")) {
                    try {
                        String timeValue = trimmed.substring(5).trim();
                        timeLimit = Float.parseFloat(timeValue);
                        System.out.println("Level time limit: " + timeLimit + " seconds");
                    } catch (NumberFormatException | IndexOutOfBoundsException e) {
                        System.err.println("Invalid time format: " + trimmed);
                    }
                } else {
                    validLines.add(trimmed);
                }
            }
        }

        if (validLines.isEmpty()) {
            return createEmptyLevel(10, 10, tileSize);
        }

        int height = validLines.size();
        int width = 0;

        int[][] tempTiles = new int[height][];
        for (int row = 0; row < height; row++) {
            String[] tokens = validLines.get(row).split("\\s+");
            tempTiles[row] = new int[tokens.length];
            
            if (tokens.length > width) {
                width = tokens.length;
            }

            for (int col = 0; col < tokens.length; col++) {
                try {
                    tempTiles[row][col] = Integer.parseInt(tokens[col]);
                } catch (NumberFormatException e) {
                    tempTiles[row][col] = 0;
                }
            }
        }

        LevelData level = new LevelData(width, height, tileSize);
        level.timeLimit = timeLimit; // Set the time limit
        
        boolean foundSpawn = false;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < tempTiles[row].length; col++) {
                int tile = tempTiles[row][col];
                level.setTile(row, col, tile);
                
                if (tile == 5 && !foundSpawn) {
                    // Store grid coordinates for spawn, conversion happens in game
                    level.playerSpawn.set(col * tileSize, row * tileSize);
                    foundSpawn = true;
                }
            }
        }

        System.out.println("Loaded level: " + filename + " (" + width + "x" + height + ")");
        return level;
    }

    private static LevelData createEmptyLevel(int width, int height, float tileSize) {
        LevelData level = new LevelData(width, height, tileSize);
        for (int col = 0; col < width; col++) {
            level.setTile(height - 1, col, 1);
        }
        level.playerSpawn.set(tileSize, height * tileSize / 2);
        return level;
    }
}
