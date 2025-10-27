package com.felipe.rehabgame;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
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
    private final float MAX_SPEED_PX_PER_SEC = 450f; // ajuste conforme necessário
    private final float TARGET_RPM_FOR_MAX_SPEED = 90f; // mapeia 90 RPM para velocidade máxima

    // pedal pulse / cadence tracking
    private final Object pulseLock = new Object();
    private long lastPulseTime = 0L; // timestamp da última batida (ms)
    private float smoothedIntervalMs = 0f; // média dos intervalos entre batidas em ms
    private final float SMOOTH_ALPHA = 0.2f; // suavização exponencial
    // se não houver pulsos por este intervalo, consideramos que o usuário parou
    private final long PULSE_TIMEOUT_MS = 1500L; // 1.5s sem pulsos => parar

    @Override
    public void create() {
        batch = new SpriteBatch();
        // atualmente usa a imagem padrão; substitua por um sprite do personagem se quiser
        playerTexture = new Texture("libgdx.png");
        font = new BitmapFont();
        font.getData().setScale(1.0f);

        // pos inicial
        playerX = 20f;
        playerY = Gdx.graphics.getHeight() * 0.3f;
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
                // mapear RPM para velocidade linear (pode trocar curva conforme necessidade)
                float t = currentRpm / TARGET_RPM_FOR_MAX_SPEED;
                if (t > 1f) t = 1f;
                if (t < 0f) t = 0f;
                speedPxPerSec = t * MAX_SPEED_PX_PER_SEC;
            } else {
                // sem pulsos recentes -> reduzir velocidade gradualmente (inércia)
                // decaimento configurável; aqui 200 px/s^2
                speedPxPerSec = Math.max(0f, speedPxPerSec - 200f * delta);
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
