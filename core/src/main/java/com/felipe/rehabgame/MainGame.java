package com.felipe.rehabgame;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.ScreenUtils;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class MainGame extends ApplicationAdapter {
    private SpriteBatch batch;
    private Texture playerTexture;
    private BitmapFont font;
    private ParallaxBackground parallax;

    private float playerX;
    private float playerY;

    // movement
    private float speedPxPerSec = 0f;
    private final float MAX_SPEED_PX_PER_SEC = 750f; // ajuste conforme necessário (cap atual)
    private final float TARGET_RPM_FOR_MAX_SPEED = 400f; // mapeia 400 RPM para velocidade máxima (cap)
    // desaceleração (px/s^2) — mantém a redução de velocidade quando o usuário para
    private final float DECELERATION_PX_PER_SEC2 = 200f; // como diminui quando para

    // pedal pulse / cadence tracking
    private final Object pulseLock = new Object();
    private long lastPulseTime = 0L;
    private float smoothedIntervalMs = 0f;
    private final float SMOOTH_ALPHA = 0.2f;
    private final long PULSE_TIMEOUT_MS = 1500L;

    // === Level System ===
    private LevelData currentLevel;
    private int currentLevelNumber = 1;
    private final int MAX_LEVEL = 3;
    private OrthographicCamera camera;
    private boolean levelComplete = false;
    private float levelCompleteTimer = 0f;
    private final float LEVEL_COMPLETE_DELAY = 2.0f; // 2 seconds delay before next level
    private boolean isLoading = true;
    private float loadingProgress = 0f;
    
    // Timer System
    private float elapsedTime = 0f;
    private boolean timeOut = false;
    private boolean gameWon = false;
    
    // Game State
    private enum GameState {
        PLAYING,
        GAME_OVER,
        VICTORY
    }
    private GameState gameState = GameState.PLAYING;
    private int selectedMenuOption = 0; // 0 = Restart, 1 = Quit

    // Camera viewport (fixed logical size)
    private final float VIEWPORT_WIDTH = 1280f;
    private final float VIEWPORT_HEIGHT = 720f;

    private Texture grassTexture;
    private Texture rampTexture;
    private Texture lakeTexture;
    private Texture flagTexture;

    // Cached level rendering
    private FrameBuffer levelFrameBuffer;
    private Texture cachedLevelTexture;

    // Physics
    private float velocityY = 0f;
    private final float GRAVITY = -980f; // pixels/s^2
    private final float RAMP_LAUNCH_VELOCITY_FACTOR = 0.5f; // Multiplier for launch speed
    @SuppressWarnings("unused")
    private boolean isOnGround = false;

    // Player rendering
    private final float PLAYER_SCALE = 0.08f; // Scale down the player texture to match tile size (~64px)

    @Override
    public void create() {
        batch = new SpriteBatch();
        camera = new OrthographicCamera();
        camera.setToOrtho(false, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);

        font = new BitmapFont();
        font.getData().setScale(1.5f);
    }

    private void loadAssets() {
        loadingProgress = 0.1f;

        // Load level from text file
        String levelFile = "level" + currentLevelNumber + ".txt";
        currentLevel = LevelLoader.loadLevel(levelFile, 64f);
        loadingProgress = 0.3f;

        // Parallax: inicialize após carregar currentLevel
        parallax = new ParallaxBackground(camera);

        // Add background layers from back (5) to front (1)
        // Layer 5 - backmost layer (slowest)
        parallax.addLayer(new Texture("Background/Background layers_layer 5.png"), 0.1f, true, false);
        
        // Layer 4
        parallax.addLayer(new Texture("Background/Background layers_layer 4.png"), 0.2f, true, false);
        
        // Layer 3
        parallax.addLayer(new Texture("Background/Background layers_layer 3.png"), 0.35f, true, false);
        
        // Layer 2
        parallax.addLayer(new Texture("Background/Background layers_layer 2.png"), 0.5f, true, false);
        
        // Layer 1 - frontmost layer (fastest)
        parallax.addLayer(new Texture("Background/Background layers_layer 1.png"), 0.7f, true, false);


        // Load tile textures from assets folder
        grassTexture = new Texture("grass.png");
        loadingProgress = 0.4f;

        rampTexture = new Texture("ramp.png");
        loadingProgress = 0.5f;

        lakeTexture = new Texture("lake.png");
        loadingProgress = 0.6f;

        flagTexture = new Texture("flag.jpg");
        loadingProgress = 0.7f;

        // Load player texture
        playerTexture = new Texture("moto.png");
        loadingProgress = 0.9f;

        // Set player start position from level
        // Spawn is stored as grid coordinates (col * tileSize, row * tileSize)
        // Level bottom row is at Y=0, top row is at (height-1) * tileSize
        int spawnCol = (int)(currentLevel.playerSpawn.x / currentLevel.tileSize);
        int spawnRow = (int)(currentLevel.playerSpawn.y / currentLevel.tileSize);

        playerX = spawnCol * currentLevel.tileSize;
        // Y coordinate: Place player ON TOP of the spawn tile
        playerY = (currentLevel.height - spawnRow) * currentLevel.tileSize;

        System.out.println("Player texture size: " + playerTexture.getWidth() + "x" + playerTexture.getHeight());
        System.out.println("Player scaled size: " + (playerTexture.getWidth() * PLAYER_SCALE) + "x" + (playerTexture.getHeight() * PLAYER_SCALE));
        System.out.println("Tile size: " + currentLevel.tileSize);
        System.out.println("Spawn position: " + playerX + ", " + playerY);

        // Pre-render level to framebuffer for performance
        buildLevelCache();

        loadingProgress = 1.0f;
        isLoading = false;
    }

    @Override
    public void render() {
        // Clear screen
        ScreenUtils.clear(0.15f, 0.15f, 0.2f, 1f);



        // Load assets on first frame
        if (isLoading) {
            renderLoadingScreen();
            loadAssets();
            return;
        }

        float delta = Gdx.graphics.getDeltaTime();
        // Cap delta time to prevent physics issues when window loses focus
        if (delta > 0.1f) {
            delta = 0.1f;
        }

        // Update timer (only if not complete and not timed out)
        if (!levelComplete && !timeOut && !gameWon) {
            elapsedTime += delta;
            
            // Check if time limit exceeded
            if (currentLevel.timeLimit > 0 && elapsedTime >= currentLevel.timeLimit) {
                timeOut = true;
                System.out.println("Time's up! Resetting level...");
                return; // Skip rest of update to freeze game state
            }
        }
        
        // Handle timeout - wait a moment then reset
        if (timeOut) {
            levelCompleteTimer += delta;
            if (levelCompleteTimer >= LEVEL_COMPLETE_DELAY) {
                gameState = GameState.GAME_OVER;
                timeOut = false;
                levelCompleteTimer = 0f;
            }
            return; // Don't process other updates while showing timeout
        }
        
        // Handle game over state - show menu
        if (gameState == GameState.GAME_OVER) {
            handleGameOverInput();
            // Continue to render the menu, don't return early
        }
        
        // Handle game won state - show menu
        else if (gameState == GameState.VICTORY) {
            handleVictoryInput();
            // Continue to render the menu, don't return early
        }

        // Input: espaço simula um pulso do dispositivo (only in playing state)
        if (gameState == GameState.PLAYING && Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            registerPedalPulse();
        }

        // Calcular velocidade atual com base no smoothedIntervalMs
        float currentRpm = 0f;
        
        // Only update game physics when in PLAYING state
        if (gameState == GameState.PLAYING) {
            long nowMs = System.currentTimeMillis();
            synchronized (pulseLock) {
                // se passou tempo demais desde o último pulso, considerar que parou
                if (lastPulseTime > 0L && (nowMs - lastPulseTime) > PULSE_TIMEOUT_MS) {
                    smoothedIntervalMs = 0f; // força o ramo de 'sem pulsos recentes'
                }

                if (smoothedIntervalMs > 0.0f) {
                    currentRpm = (60_000f / smoothedIntervalMs); // ms -> RPM
                    // mapeamento linear direto: RPM -> target speed
                    float t = currentRpm / TARGET_RPM_FOR_MAX_SPEED;
                    if (t > 1f) t = 1f;
                    if (t < 0f) t = 0f;
                    float targetSpeed = t * MAX_SPEED_PX_PER_SEC;

                    // Se o alvo for menor que a velocidade atual, desacelerar gradualmente
                    if (targetSpeed < speedPxPerSec) {
                        speedPxPerSec = Math.max(targetSpeed, speedPxPerSec - DECELERATION_PX_PER_SEC2 * delta);
                    } else {
                        // Se o alvo for maior, aplicar imediatamente (controle responsivo ao aumento de RPM)
                        speedPxPerSec = targetSpeed;
                    }
                } else {
                    // sem pulsos recentes -> reduzir velocidade gradualmente (inércia)
                    speedPxPerSec = Math.max(0f, speedPxPerSec - DECELERATION_PX_PER_SEC2 * delta);
                }
            }

            // Apply physics
            velocityY += GRAVITY * delta;
            playerY += velocityY * delta;

            //speed ambiente
            parallax.update(speedPxPerSec, delta);

            // mover personagem horizontalmente
            playerX += speedPxPerSec * delta;

            // Check ground and ramp collision
            checkGroundAndRampCollision();

            // Check lake collision (game over)
            checkLakeCollision();

            // Check flag collision
            if (!levelComplete) {
                checkFlagCollision();
            }

            // Handle level completion and progression
            if (levelComplete) {
                levelCompleteTimer += delta;
                if (levelCompleteTimer >= LEVEL_COMPLETE_DELAY) {
                    loadNextLevel();
                }
            }
        }

        // Update camera to follow player (only when playing)
        if (gameState == GameState.PLAYING) {
            float camX = playerX + (playerTexture.getWidth() * PLAYER_SCALE) / 2;
            float camY = Math.max(VIEWPORT_HEIGHT / 2, Math.min(playerY + (playerTexture.getHeight() * PLAYER_SCALE) / 2, (currentLevel.height * currentLevel.tileSize) - VIEWPORT_HEIGHT / 2));
            camera.position.set(camX, camY, 0);
            camera.update();
        }

        // desenho (only draw game when playing)
        if (gameState == GameState.PLAYING) {
            batch.setProjectionMatrix(camera.combined);
            batch.begin();

            //desenho paralaxe
            parallax.draw(batch);

            // Draw cached level (much faster than drawing individual tiles)
            if (cachedLevelTexture != null) {
                int levelWidth = (int)(currentLevel.width * currentLevel.tileSize);
                int levelHeight = (int)(currentLevel.height * currentLevel.tileSize);
                batch.draw(cachedLevelTexture, 0, 0, levelWidth, levelHeight);
            }

            // Draw player (scaled down)
            float scaledWidth = playerTexture.getWidth() * PLAYER_SCALE;
            float scaledHeight = playerTexture.getHeight() * PLAYER_SCALE;
            batch.draw(playerTexture, playerX, playerY, scaledWidth, scaledHeight);

            batch.end();
        }

        // Draw HUD (fixed on screen using screen coordinates) - drawn LAST to be on top
        OrthographicCamera hudCamera = new OrthographicCamera();
        hudCamera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        hudCamera.update();
        batch.setProjectionMatrix(hudCamera.combined);
        batch.begin();

        // Only show game HUD when playing
        if (gameState == GameState.PLAYING) {
            // HUD
            String hud = String.format("RPM: %.1f  Speed: %.0f px/s  Y-Vel: %.0f (SPACE=Pedal)", currentRpm, speedPxPerSec, velocityY);
            font.draw(batch, hud, 10, Gdx.graphics.getHeight() - 10);
            
            // Timer display
            if (currentLevel.timeLimit > 0) {
                float remainingTime = currentLevel.timeLimit - elapsedTime;
                if (remainingTime < 0) remainingTime = 0;
                int minutes = (int)(remainingTime / 60);
                int seconds = (int)(remainingTime % 60);
                String timerColor = remainingTime < 10 ? "TIME: " : "TIME: ";
                String timerText = String.format("%s%d:%02d", timerColor, minutes, seconds);
                font.draw(batch, timerText, 10, Gdx.graphics.getHeight() - 40);
            }
            
            // Level info
            String levelInfo = String.format("Level %d/%d", currentLevelNumber, MAX_LEVEL);
            font.draw(batch, levelInfo, 10, Gdx.graphics.getHeight() - 70);

            if (timeOut) {
                font.getData().setScale(3.0f);
                font.draw(batch, "TIME'S UP!", Gdx.graphics.getWidth() / 2 - 150, Gdx.graphics.getHeight() / 2);
                font.getData().setScale(1.5f);
                int timeLeft = (int)(LEVEL_COMPLETE_DELAY - levelCompleteTimer);
                font.draw(batch, "Resetting in " + (timeLeft + 1) + "...", Gdx.graphics.getWidth() / 2 - 100, Gdx.graphics.getHeight() / 2 - 50);
                font.getData().setScale(1.5f);
            } else if (levelComplete) {
                font.getData().setScale(3.0f);
                String message = currentLevelNumber >= MAX_LEVEL ? "LEVEL COMPLETE!" : "LEVEL COMPLETE!";
                font.draw(batch, message, Gdx.graphics.getWidth() / 2 - 250, Gdx.graphics.getHeight() / 2);

                if (currentLevelNumber < MAX_LEVEL) {
                    font.getData().setScale(1.5f);
                    int timeLeft = (int)(LEVEL_COMPLETE_DELAY - levelCompleteTimer);
                    font.draw(batch, "Next level in " + (timeLeft + 1) + "...", Gdx.graphics.getWidth() / 2 - 100, Gdx.graphics.getHeight() / 2 - 50);
                }
                font.getData().setScale(1.5f);
            }
        }
        
        // Draw menus over everything
        if (gameState == GameState.GAME_OVER) {
            renderGameOverMenu();
        } else if (gameState == GameState.VICTORY) {
            renderVictoryMenu();
        }        if (timeOut) {
            font.getData().setScale(3.0f);
            font.draw(batch, "TIME'S UP!", Gdx.graphics.getWidth() / 2 - 150, Gdx.graphics.getHeight() / 2);
            font.getData().setScale(1.5f);
            int timeLeft = (int)(LEVEL_COMPLETE_DELAY - levelCompleteTimer);
            font.draw(batch, "Resetting in " + (timeLeft + 1) + "...", Gdx.graphics.getWidth() / 2 - 100, Gdx.graphics.getHeight() / 2 - 50);
            font.getData().setScale(1.5f);
        } else if (gameState == GameState.GAME_OVER) {
            renderGameOverMenu();
        } else if (gameState == GameState.VICTORY) {
            renderVictoryMenu();
        } else if (levelComplete) {
            font.getData().setScale(3.0f);
            String message = currentLevelNumber >= MAX_LEVEL ? "LEVEL COMPLETE!" : "LEVEL COMPLETE!";
            font.draw(batch, message, Gdx.graphics.getWidth() / 2 - 250, Gdx.graphics.getHeight() / 2);

            if (currentLevelNumber < MAX_LEVEL) {
                font.getData().setScale(1.5f);
                int timeLeft = (int)(LEVEL_COMPLETE_DELAY - levelCompleteTimer);
                font.draw(batch, "Next level in " + (timeLeft + 1) + "...", Gdx.graphics.getWidth() / 2 - 100, Gdx.graphics.getHeight() / 2 - 50);
            }
            font.getData().setScale(1.5f);
        }

        batch.end();
    }

    private void renderLoadingScreen() {
        batch.begin();
        font.getData().setScale(2.0f);
        font.draw(batch, "LOADING...", Gdx.graphics.getWidth() / 2 - 80, Gdx.graphics.getHeight() / 2 + 50);
        font.getData().setScale(1.5f);

        int progress = (int)(loadingProgress * 100);
        font.draw(batch, progress + "%", Gdx.graphics.getWidth() / 2 - 20, Gdx.graphics.getHeight() / 2);
        batch.end();
    }

    @Override
    public void dispose() {
        batch.dispose();
        playerTexture.dispose();
        font.dispose();

        if (parallax != null) parallax.dispose();
        if (grassTexture != null) grassTexture.dispose();
        if (rampTexture != null) rampTexture.dispose();
        if (lakeTexture != null) lakeTexture.dispose();
        if (flagTexture != null) flagTexture.dispose();
        if (levelFrameBuffer != null) levelFrameBuffer.dispose();
        if (cachedLevelTexture != null) cachedLevelTexture.dispose();
    }

    private void buildLevelCache() {
        int levelWidth = (int)(currentLevel.width * currentLevel.tileSize);
        int levelHeight = (int)(currentLevel.height * currentLevel.tileSize);

        System.out.println("Building level cache: " + levelWidth + "x" + levelHeight);

        // Create framebuffer to render level once
        levelFrameBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, levelWidth, levelHeight, false);

        // Render all tiles to the framebuffer
        levelFrameBuffer.begin();
        Gdx.gl.glClearColor(0, 0, 0, 0);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // Create temporary camera for rendering the full level
        OrthographicCamera tempCam = new OrthographicCamera();
        tempCam.setToOrtho(true, levelWidth, levelHeight); // true flips Y axis
        tempCam.position.set(levelWidth / 2, levelHeight / 2, 0);
        tempCam.update();

        batch.setProjectionMatrix(tempCam.combined);
        batch.begin();

        // Draw all tiles
        int tileCount = 0;
        for (int row = 0; row < currentLevel.height; row++) {
            for (int col = 0; col < currentLevel.width; col++) {
                int tile = currentLevel.getTile(row, col);
                if (tile == 0 || tile == 5) continue;

                float worldX = col * currentLevel.tileSize;
                float worldY = (currentLevel.height - row - 1) * currentLevel.tileSize;

                Texture texture = getTileTexture(tile);

                if (texture != null) {
                    batch.draw(texture, worldX, worldY, currentLevel.tileSize, currentLevel.tileSize);
                    tileCount++;
                }
            }
        }

        batch.end();
        levelFrameBuffer.end();

        // Get the texture from framebuffer
        cachedLevelTexture = levelFrameBuffer.getColorBufferTexture();

        System.out.println("Level cached! Drew " + tileCount + " tiles once.");
    }

    private Texture getTileTexture(int tileType) {
        switch (tileType) {
            case 1: return grassTexture;
            case 2: return rampTexture;
            case 3: return lakeTexture;
            case 4: return flagTexture;
            default: return null;
        }
    }

    private void checkGroundAndRampCollision() {
        float playerWidth = playerTexture.getWidth() * PLAYER_SCALE;
        float playerHeight = playerTexture.getHeight() * PLAYER_SCALE;
    
        // Find the highest ground/ramp position under the player
        float highestY = -1;
        boolean onRamp = false;
    
        int startCol = Math.max(0, (int)(playerX / currentLevel.tileSize));
        int endCol = Math.min(currentLevel.width - 1, (int)((playerX + playerWidth) / currentLevel.tileSize));
    
        for (int row = 0; row < currentLevel.height; row++) {
            for (int col = startCol; col <= endCol; col++) {
                int tile = currentLevel.getTile(row, col);
                float worldX = col * currentLevel.tileSize;
                float worldY = (currentLevel.height - row - 1) * currentLevel.tileSize;
    
                if (tile == 1) { // Grass
                    if (playerX + playerWidth > worldX && playerX < worldX + currentLevel.tileSize) {
                        highestY = Math.max(highestY, worldY + currentLevel.tileSize);
                    }
                } else if (tile == 2) { // Ramp
                    if (playerX + playerWidth > worldX && playerX < worldX + currentLevel.tileSize) {
                        float relativeX = (playerX + playerWidth / 2) - worldX;
                        float rampHeight = (relativeX / currentLevel.tileSize) * currentLevel.tileSize;
                        float currentRampY = worldY + rampHeight;
                        if (currentRampY > highestY) {
                            highestY = currentRampY;
                            onRamp = true;
                        }
                    }
                }
            }
        }
    
        // Apply collision response
        if (highestY != -1 && playerY <= highestY) {
            playerY = highestY;
            if (onRamp) {
                // On a ramp, velocity is influenced by speed
                velocityY = speedPxPerSec * RAMP_LAUNCH_VELOCITY_FACTOR;
                isOnGround = false;
            } else {
                // On flat ground
                velocityY = 0;
                isOnGround = true;
            }
        } else {
            isOnGround = false;
        }
    }

    private void checkLakeCollision() {
        float playerWidth = playerTexture.getWidth() * PLAYER_SCALE;
        float playerHeight = playerTexture.getHeight() * PLAYER_SCALE;
        Rectangle playerBox = new Rectangle(playerX, playerY, playerWidth, playerHeight);

        // Check tiles near player for better performance
        int startCol = Math.max(0, (int)(playerX / currentLevel.tileSize) - 1);
        int endCol = Math.min(currentLevel.width - 1, (int)((playerX + playerWidth) / currentLevel.tileSize) + 1);

        for (int row = 0; row < currentLevel.height; row++) {
            for (int col = startCol; col <= endCol; col++) {
                if (currentLevel.getTile(row, col) == 3) { // Lake tile
                    float worldX = col * currentLevel.tileSize;
                    float worldY = (currentLevel.height - row - 1) * currentLevel.tileSize;

                    Rectangle tileBox = new Rectangle(worldX, worldY, currentLevel.tileSize, currentLevel.tileSize);

                    if (playerBox.overlaps(tileBox)) {
                        // Game over - hit the lake
                        gameState = GameState.GAME_OVER;
                        System.out.println("Hit the lake! Game Over!");
                        return;
                    }
                }
            }
        }
    }

    private void checkFlagCollision() {
        if (levelComplete) return;

        float playerWidth = playerTexture.getWidth() * PLAYER_SCALE;
        float playerHeight = playerTexture.getHeight() * PLAYER_SCALE;
        Rectangle playerBox = new Rectangle(playerX, playerY, playerWidth, playerHeight);

        for (int row = 0; row < currentLevel.height; row++) {
            for (int col = 0; col < currentLevel.width; col++) {
                if (currentLevel.getTile(row, col) == 4) {
                    float worldX = col * currentLevel.tileSize;
                    float worldY = (currentLevel.height - row - 1) * currentLevel.tileSize;

                    Rectangle tileBox = new Rectangle(worldX, worldY, currentLevel.tileSize, currentLevel.tileSize);

                    if (playerBox.overlaps(tileBox)) {
                        levelComplete = true;
                        
                        // Check if this is the last level - if so, player wins!
                        if (currentLevelNumber >= MAX_LEVEL) {
                            gameWon = true;
                            gameState = GameState.VICTORY;
                            System.out.println("YOU WIN! All levels completed!");
                        } else {
                            System.out.println("Level Complete!");
                        }
                        return;
                    }
                }
            }
        }
    }

    private void resetPlayer() {
        // Reset player to spawn position
        int spawnCol = (int)(currentLevel.playerSpawn.x / currentLevel.tileSize);
        int spawnRow = (int)(currentLevel.playerSpawn.y / currentLevel.tileSize);

        playerX = spawnCol * currentLevel.tileSize;
        // Place player ON TOP of the spawn tile
        playerY = (currentLevel.height - spawnRow) * currentLevel.tileSize;

        // Reset physics
        velocityY = 0f;
        speedPxPerSec = 0f;
        isOnGround = false;
        
        // Reset timer
        elapsedTime = 0f;

        // Reset pedal tracking
        synchronized (pulseLock) {
            lastPulseTime = 0L;
            smoothedIntervalMs = 0f;
        }
    }

    private void loadNextLevel() {
        if (currentLevelNumber >= MAX_LEVEL) {
            System.out.println("All levels completed!");
            return;
        }

        // Increment level
        currentLevelNumber++;
        System.out.println("Loading level " + currentLevelNumber);

        // Dispose old level cache
        if (levelFrameBuffer != null) {
            levelFrameBuffer.dispose();
            levelFrameBuffer = null;
        }
        if (cachedLevelTexture != null) {
            cachedLevelTexture = null;
        }

        // Load new level
        String levelFile = "level" + currentLevelNumber + ".txt";
        currentLevel = LevelLoader.loadLevel(levelFile, 64f);

        // Reset player to new spawn
        int spawnCol = (int)(currentLevel.playerSpawn.x / currentLevel.tileSize);
        int spawnRow = (int)(currentLevel.playerSpawn.y / currentLevel.tileSize);

        playerX = spawnCol * currentLevel.tileSize;
        // Place player ON TOP of the spawn tile
        playerY = (currentLevel.height - spawnRow) * currentLevel.tileSize;

        // Reset physics and state
        velocityY = 0f;
        speedPxPerSec = 0f;
        isOnGround = false;
        levelComplete = false;
        levelCompleteTimer = 0f;
        
        // Reset timer
        elapsedTime = 0f;
        timeOut = false;

        // Reset pedal tracking
        synchronized (pulseLock) {
            lastPulseTime = 0L;
            smoothedIntervalMs = 0f;
        }

        // Rebuild level cache
        buildLevelCache();
    }

    /**
     * Deve ser chamada quando um pulso de pedal for detectado (do IoT ou do teclado).
     * Thread-safe: pode ser chamada a partir de um listener de rede/serial.
     */
    public void registerPedalPulse() {
        long now = System.currentTimeMillis();
        synchronized (pulseLock) {
            if (lastPulseTime > 0L) {
                float interval = (float) (now - lastPulseTime); // ms
                if (smoothedIntervalMs <= 0f) {
                    smoothedIntervalMs = interval;
                } else {
                    // suavização exponencial para estabilidade
                    smoothedIntervalMs = SMOOTH_ALPHA * interval + (1f - SMOOTH_ALPHA) * smoothedIntervalMs;
                }
            }
            lastPulseTime = now;
        }
    }
    
    private void renderGameOverMenu() {
        int centerX = Gdx.graphics.getWidth() / 2;
        int centerY = Gdx.graphics.getHeight() / 2;
        
        // Title
        font.getData().setScale(3.5f);
        font.draw(batch, "GAME OVER", centerX - 180, centerY + 100);
        
        // Subtitle
        font.getData().setScale(1.8f);
        font.draw(batch, "Time's Up!", centerX - 80, centerY + 40);
        
        // Menu options
        font.getData().setScale(2.0f);
        String restartText = selectedMenuOption == 0 ? "> RESTART LEVEL" : "  RESTART LEVEL";
        String quitText = selectedMenuOption == 1 ? "> QUIT" : "  QUIT";
        
        font.draw(batch, restartText, centerX - 150, centerY - 20);
        font.draw(batch, quitText, centerX - 150, centerY - 60);
        
        // Instructions
        font.getData().setScale(1.2f);
        font.draw(batch, "Use UP/DOWN arrows and ENTER", centerX - 180, centerY - 120);
        
        font.getData().setScale(1.5f);
    }
    
    private void renderVictoryMenu() {
        int centerX = Gdx.graphics.getWidth() / 2;
        int centerY = Gdx.graphics.getHeight() / 2;
        
        // Title
        font.getData().setScale(4.0f);
        font.draw(batch, "YOU WIN!", centerX - 150, centerY + 120);
        
        // Subtitle
        font.getData().setScale(2.0f);
        font.draw(batch, "All Levels Completed!", centerX - 180, centerY + 60);
        
        // Menu options
        font.getData().setScale(2.0f);
        String restartText = selectedMenuOption == 0 ? "> PLAY AGAIN" : "  PLAY AGAIN";
        String quitText = selectedMenuOption == 1 ? "> QUIT" : "  QUIT";
        
        font.draw(batch, restartText, centerX - 150, centerY - 20);
        font.draw(batch, quitText, centerX - 150, centerY - 60);
        
        // Instructions
        font.getData().setScale(1.2f);
        font.draw(batch, "Use UP/DOWN arrows and ENTER", centerX - 180, centerY - 120);
        
        font.getData().setScale(1.5f);
    }
    
    private void handleGameOverInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            selectedMenuOption = 0;
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            selectedMenuOption = 1;
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            if (selectedMenuOption == 0) {
                // Restart current level
                restartCurrentLevel();
            } else {
                // Quit game
                Gdx.app.exit();
            }
        }
    }
    
    private void handleVictoryInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            selectedMenuOption = 0;
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            selectedMenuOption = 1;
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            if (selectedMenuOption == 0) {
                // Restart from level 1
                restartGame();
            } else {
                // Quit game
                Gdx.app.exit();
            }
        }
    }
    
    private void restartCurrentLevel() {
        // Reset to current level
        resetPlayer();
        elapsedTime = 0f;
        gameState = GameState.PLAYING;
        selectedMenuOption = 0;
    }
    
    private void restartGame() {
        // Dispose old level cache
        if (levelFrameBuffer != null) {
            levelFrameBuffer.dispose();
            levelFrameBuffer = null;
        }
        if (cachedLevelTexture != null) {
            cachedLevelTexture = null;
        }
        
        // Reset to level 1
        currentLevelNumber = 1;
        String levelFile = "level" + currentLevelNumber + ".txt";
        currentLevel = LevelLoader.loadLevel(levelFile, 64f);
        
        // Reset player
        int spawnCol = (int)(currentLevel.playerSpawn.x / currentLevel.tileSize);
        int spawnRow = (int)(currentLevel.playerSpawn.y / currentLevel.tileSize);
        playerX = spawnCol * currentLevel.tileSize;
        playerY = (currentLevel.height - spawnRow) * currentLevel.tileSize;
        
        // Reset all state
        velocityY = 0f;
        speedPxPerSec = 0f;
        isOnGround = false;
        levelComplete = false;
        levelCompleteTimer = 0f;
        elapsedTime = 0f;
        timeOut = false;
        gameWon = false;
        gameState = GameState.PLAYING;
        selectedMenuOption = 0;
        
        synchronized (pulseLock) {
            lastPulseTime = 0L;
            smoothedIntervalMs = 0f;
        }
        
        // Rebuild level cache
        buildLevelCache();
    }
}
