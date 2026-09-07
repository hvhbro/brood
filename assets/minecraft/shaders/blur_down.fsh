#version 130

uniform sampler2D uSource;
uniform vec2 uHalfPixel;
uniform float uOffset;

in vec2 passUv;
out vec4 fragColor;

void main() {
    vec4 sum = texture(uSource, passUv) * 4.0;
    sum += texture(uSource, passUv - uHalfPixel * uOffset);
    sum += texture(uSource, passUv + uHalfPixel * uOffset);
    sum += texture(uSource, passUv + vec2(uHalfPixel.x, -uHalfPixel.y) * uOffset);
    sum += texture(uSource, passUv - vec2(uHalfPixel.x, -uHalfPixel.y) * uOffset);
    fragColor = sum / 8.0;
}
