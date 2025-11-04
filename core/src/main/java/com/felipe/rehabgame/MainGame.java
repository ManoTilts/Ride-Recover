package com.felipe.rehabgame;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ScreenUtils;

import java.util.ArrayList;
import java.util.List;

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
    private long lastPulseTime = 0L; // timestamp da última batida (ms)
    private float smoothedIntervalMs = 0f; // média dos intervalos entre batidas em ms
    private final float SMOOTH_ALPHA = 0.2f; // suavização exponencial
    // se não houver pulsos por este intervalo, consideramos que o usuário parou
    private final long PULSE_TIMEOUT_MS = 1500L; // 1.5s sem pulsos => parar

    // === NOVO: integração do mapa HyperLap2D ===
    private TextureAtlas mapAtlas;               // atlas do HyperLap2D
    private List<Sprite> mapSprites;             // sprites da cena

    @Override
    public void create() {
        batch = new SpriteBatch();

        // === NOVO: carregar mapa do HyperLap2D ===
        loadMap("pack.atlas", "MainScene.dt");

        // atualmente usa a imagem padrão; substitua por um sprite do personagem se quiser
        playerTexture = new Texture("moto.png");
        font = new BitmapFont();
        font.getData().setScale(1.0f);

        // pos inicial
        playerX = 20f;
        playerY = Gdx.graphics.getHeight() * 0.3f;
    }

    /**
     * === NOVO ===
     * Carrega o atlas e o JSON exportado pelo HyperLap2D e monta os sprites do mapa
     */
    private void loadMap(String atlasPath, String sceneJsonPath) {
        mapAtlas = new TextureAtlas(Gdx.files.internal(atlasPath)); // carregar o atlas
        mapSprites = new ArrayList<>();

        // ler o JSON (arquivo .dt é JSON na prática)
        JsonReader reader = new JsonReader();
        JsonValue root = reader.parse(Gdx.files.internal(sceneJsonPath));

        // acessar array de imagens (SimpleImageVO)
        JsonValue images = root.get("composite").get("content")
            .get("games.rednblack.editor.renderer.data.SimpleImageVO");

        for (JsonValue img : images) {
            String imageName = img.getString("imageName");
            float x = img.getFloat("x");
            float y = img.getFloat("y");
            float originX = img.getFloat("originX", 0);
            float originY = img.getFloat("originY", 0);

            Sprite sprite = mapAtlas.createSprite(imageName);
            if (sprite != null) {
                sprite.setPosition(x, y);
                sprite.setOrigin(originX, originY);
                mapSprites.add(sprite);
            } else {
                System.out.println("Sprite não encontrado no atlas: " + imageName);
            }
        }
    }

    @Override
    public void render() {
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

        // mover personagem
        playerX += speedPxPerSec * delta;

        // wrap quando chega no fim da tela
        if (playerX > Gdx.graphics.getWidth()) {
            playerX = -playerTexture.getWidth();
        }

        // desenho
        ScreenUtils.clear(0.15f, 0.15f, 0.2f, 1f);
        batch.begin();

        // === NOVO: desenhar todos os sprites do mapa primeiro ===
        for (Sprite s : mapSprites) {
            s.draw(batch);
        }

        batch.draw(playerTexture, playerX, playerY);

        // HUD
        String hud = String.format("RPM: %.1f  Speed: %.0f px/s  (Press SPACE to simulate)", currentRpm, speedPxPerSec);
        font.draw(batch, hud, 10, Gdx.graphics.getHeight() - 10);
        batch.end();
    }

    @Override
    public void dispose() {
        batch.dispose();
        playerTexture.dispose();
        font.dispose();

        // === NOVO: descarta recursos do mapa ===
        if (mapAtlas != null) mapAtlas.dispose();
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
