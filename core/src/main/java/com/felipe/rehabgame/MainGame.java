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

    private float playerX;
    private float playerY;

    // movement
    private float speedPxPerSec = 0f;
    private final float MAX_SPEED_PX_PER_SEC = 500f; // ajuste conforme necessário (cap atual)
    private final float TARGET_RPM_FOR_MAX_SPEED = 300f; // mapeia 300 RPM para velocidade máxima (cap)
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
    private OrthographicCamera camera;
    private boolean levelComplete = false;
    private boolean isLoading = true;
    private float loadingProgress = 0f;
    
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
    private final float RAMP_LAUNCH_ANGLE = 45f; // Degrees - natural ramp angle for launch
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
        currentLevel = LevelLoader.loadLevel("level1.txt", 64f);
        loadingProgress = 0.3f;

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
        // Y coordinate: bottom of level is 0, so row 0 = top, row (height-1) = bottom
        playerY = (currentLevel.height - spawnRow - 1) * currentLevel.tileSize;
        
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
        ScreenUtils.clear(0.15f, 0.15f, 0.2f, 1f);
        
        // Load assets on first frame
        if (isLoading) {
            renderLoadingScreen();
            loadAssets();
            return;
        }
        
        float delta = Gdx.graphics.getDeltaTime();

        // Input: espaço simula um pulso do dispositivo
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            registerPedalPulse();
        }

        // Calcular velocidade atual com base no smoothedIntervalMs
        float currentRpm = 0f;
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
        
        // mover personagem horizontalmente
        playerX += speedPxPerSec * delta;

        // Check ground and ramp collision
        checkGroundAndRampCollision();
        
        // Check flag collision
        checkFlagCollision();

        // Update camera to follow player
        float camX = playerX + (playerTexture.getWidth() * PLAYER_SCALE) / 2;
        float camY = Math.max(VIEWPORT_HEIGHT / 2, Math.min(playerY + (playerTexture.getHeight() * PLAYER_SCALE) / 2, (currentLevel.height * currentLevel.tileSize) - VIEWPORT_HEIGHT / 2));
        camera.position.set(camX, camY, 0);
        camera.update();

        // desenho
        batch.setProjectionMatrix(camera.combined);
        batch.begin();

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
        
        // Draw HUD (fixed on screen using screen coordinates)
        batch.setProjectionMatrix(batch.getProjectionMatrix().idt());
        batch.begin();

        // HUD
        String hud = String.format("RPM: %.1f  Speed: %.0f px/s  Y-Vel: %.0f (SPACE=Pedal)", currentRpm, speedPxPerSec, velocityY);
        font.draw(batch, hud, 10, Gdx.graphics.getHeight() - 10);
        
        if (levelComplete) {
            font.getData().setScale(3.0f);
            font.draw(batch, "LEVEL COMPLETE!", Gdx.graphics.getWidth() / 2 - 200, Gdx.graphics.getHeight() / 2);
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
        
        Rectangle playerBox = new Rectangle(playerX, playerY, playerWidth, playerHeight);
        isOnGround = false;
        boolean hitRamp = false;
        
        // Check tiles near player for better performance
        int startCol = Math.max(0, (int)(playerX / currentLevel.tileSize) - 1);
        int endCol = Math.min(currentLevel.width - 1, (int)((playerX + playerWidth) / currentLevel.tileSize) + 1);
        
        for (int row = 0; row < currentLevel.height; row++) {
            for (int col = startCol; col <= endCol; col++) {
                int tile = currentLevel.getTile(row, col);
                
                // Check floor (grass) - acts as solid barrier
                if (tile == 1) {
                    float worldX = col * currentLevel.tileSize;
                    float worldY = (currentLevel.height - row - 1) * currentLevel.tileSize;
                    
                    Rectangle tileBox = new Rectangle(worldX, worldY, currentLevel.tileSize, currentLevel.tileSize);
                    
                    if (playerBox.overlaps(tileBox)) {
                        // Calculate overlap on each axis
                        float overlapX = Math.min(playerX + playerWidth, worldX + currentLevel.tileSize) - Math.max(playerX, worldX);
                        float overlapY = Math.min(playerY + playerHeight, worldY + currentLevel.tileSize) - Math.max(playerY, worldY);
                        
                        // Determine collision direction based on smallest overlap and velocity
                        if (overlapY < overlapX) {
                            // Vertical collision (top or bottom)
                            if (velocityY <= 0 && playerY < worldY + currentLevel.tileSize) {
                                // Coming from above - land on top
                                playerY = worldY + currentLevel.tileSize;
                                velocityY = 0;
                                isOnGround = true;
                            } else if (velocityY > 0 && playerY + playerHeight > worldY) {
                                // Coming from below - hit bottom
                                playerY = worldY - playerHeight;
                                velocityY = 0;
                            }
                        } else {
                            // Horizontal collision (left or right) - act as wall
                            if (playerX + playerWidth > worldX && playerX < worldX) {
                                // Hitting from left - push player back
                                playerX = worldX - playerWidth;
                                speedPxPerSec = 0; // Stop horizontal movement
                            } else if (playerX < worldX + currentLevel.tileSize && playerX + playerWidth > worldX + currentLevel.tileSize) {
                                // Hitting from right - push player forward
                                playerX = worldX + currentLevel.tileSize;
                                speedPxPerSec = 0; // Stop horizontal movement
                            }
                        }
                    }
                }
                // Check ramp - acts like a slope that redirects velocity
                else if (tile == 2 && !hitRamp) {
                    float worldX = col * currentLevel.tileSize;
                    float worldY = (currentLevel.height - row - 1) * currentLevel.tileSize;
                    
                    Rectangle tileBox = new Rectangle(worldX, worldY, currentLevel.tileSize, currentLevel.tileSize);
                    
                    if (playerBox.overlaps(tileBox)) {
                        hitRamp = true;
                        
                        // Check if there's another ramp tile to the right (extended ramp)
                        boolean hasRampToRight = false;
                        if (col + 1 < currentLevel.width) {
                            int nextTile = currentLevel.getTile(row, col + 1);
                            hasRampToRight = (nextTile == 2);
                        }
                        
                        // Check if player is at the top edge of the ramp (launching off)
                        float playerBottomY = playerY;
                        float rampTopY = worldY + currentLevel.tileSize;
                        float playerRightX = playerX + playerWidth;
                        float rampRightX = worldX + currentLevel.tileSize;
                        
                        // Only launch if at the edge AND no more ramp tiles to the right
                        if (!hasRampToRight && playerRightX > rampRightX - 10 && playerBottomY >= rampTopY - 10) {
                            // Launch at angle based on horizontal speed
                            double angleRad = Math.toRadians(RAMP_LAUNCH_ANGLE);
                            velocityY = (float)(speedPxPerSec * Math.sin(angleRad));
                            isOnGround = false;
                        } else {
                            // Player is on the ramp surface - keep them grounded
                            if (velocityY <= 0 && playerY < worldY + currentLevel.tileSize) {
                                playerY = worldY + currentLevel.tileSize;
                                velocityY = 0;
                                isOnGround = true;
                            }
                        }
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
                        System.out.println("Level Complete!");
                        return;
                    }
                }
            }
        }
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
}
