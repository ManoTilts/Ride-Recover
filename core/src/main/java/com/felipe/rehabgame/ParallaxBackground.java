package com.felipe.rehabgame;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.OrthographicCamera;
import java.util.ArrayList;
import java.util.List;

/**
 * ParallaxBackground ligado diretamente à câmera.
 *
 * Uso recomendado:
 * - Inicialize *após* carregar o Level (para saber worldWidth se quiser).
 * - Preferência: ancore o parallax na câmera (ele já usa a camera passada).
 */
public class ParallaxBackground {

    public static class Layer {
        public final Texture texture;
        public final float speed; // 0 = fixo, 1 = move com a câmera
        public final boolean stretchToViewportHeight; // se true: estica para cobrir a altura da viewport
        public final boolean repeatY; // se true: repete verticalmente em vez de esticar

        public Layer(Texture texture, float speed, boolean stretchToViewportHeight, boolean repeatY) {
            this.texture = texture;
            this.speed = speed;
            this.stretchToViewportHeight = stretchToViewportHeight;
            this.repeatY = repeatY;
        }
    }

    private final OrthographicCamera camera;
    private final List<Layer> layers = new ArrayList<>();

    public ParallaxBackground(OrthographicCamera camera) {
        this.camera = camera;
    }

    /** Adiciona camada. Recomendo: background (stretch=true), trees (stretch=false). */
    public void addLayer(Texture texture, float speed, boolean stretchToViewportHeight, boolean repeatY) {
        layers.add(new Layer(texture, speed, stretchToViewportHeight, repeatY));
    }

    /** Versão abreviada: sem opções verticais (stretch=true por padrão) */
    public void addLayer(Texture texture, float speed) {
        addLayer(texture, speed, true, false);
    }

    /** Atualização opcional — mantido para compatibilidade com seu código. */
    public void update(float playerSpeed, float delta) {
        // Nada necessário aqui: o desenho é calculado a partir da posição da câmera.
        // Mantemos o método para compatibilidade com chamadas existentes.
    }

    /**
     * Desenha todas as camadas garantindo cobertura horizontal (e vertical conforme opções).
     * Deve ser chamado entre batch.begin() / batch.end() com projectionMatrix = camera.combined.
     */
    public void draw(SpriteBatch batch) {
        float camLeft = camera.position.x - camera.viewportWidth / 2f;
        float camRight = camera.position.x + camera.viewportWidth / 2f;
        float camBottom = camera.position.y - camera.viewportHeight / 2f;
        float camTop = camera.position.y + camera.viewportHeight / 2f;

        for (Layer layer : layers) {
            Texture tex = layer.texture;
            float texW = tex.getWidth();
            float texH = tex.getHeight();

            // deslocamento do layer em pixels; se speed=0 => fixo, se speed=1 => acompanha a câmera
            float layerScroll = camLeft * layer.speed;

            // offset dentro da largura da textura (0..texW)
            float offset = layerScroll - (float)Math.floor(layerScroll / texW) * texW;
            // startX é a primeira posição a desenhar (pode ser < camLeft)
            float startX = camLeft - offset;

            // desenho horizontal: desde startX até camRight + texW para garantir cobertura
            // calculamos um número suficiente de repetições:
            int repeatCount = (int)Math.ceil((camera.viewportWidth + texW) / texW) + 2;

            // vertical: decidir Y inicial e altura de desenho
            float drawYStart;
            float drawHeight;
            if (layer.stretchToViewportHeight) {
                // estica para preencher a altura da viewport
                drawYStart = camBottom;
                drawHeight = camera.viewportHeight;
                // desenhar uma linha vertical apenas
                for (int i = 0; i < repeatCount; i++) {
                    float x = startX + i * texW;
                    batch.draw(tex, x, drawYStart, texW, drawHeight);
                }
            } else if (layer.repeatY) {
                // repete verticalmente para cobrir a viewport
                // começamos um tile abaixo do bottom para garantir cobertura
                float yOffset = camBottom - texH;
                int rows = (int)Math.ceil((camera.viewportHeight + texH) / texH) + 2;
                for (int i = 0; i < repeatCount; i++) {
                    float x = startX + i * texW;
                    for (int r = 0; r < rows; r++) {
                        float y = yOffset + r * texH;
                        batch.draw(tex, x, y);
                    }
                }
            } else {
                // desenha a textura no centro vertical relativo à câmera (não estica nem repete)
                // centralizamos verticalmente na viewport (ou coloque y em 0 se preferir)
                float y = camBottom + (camera.viewportHeight - texH) / 2f;
                for (int i = 0; i < repeatCount; i++) {
                    float x = startX + i * texW;
                    batch.draw(tex, x, y);
                }
            }
        }
    }

    /** Descarta texturas se quiser que o Parallax gerencie elas. */
    public void dispose() {
        for (Layer l : layers) {
            if (l.texture != null) {
                try {
                    l.texture.dispose();
                } catch (Exception e) {
                    // ignore - caller pode já ter gerenciado a textura
                }
            }
        }
    }
}
