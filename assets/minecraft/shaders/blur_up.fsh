#version 130

uniform sampler2D uSource;
uniform vec2 uHalfPixel;
uniform float uOffset;

in vec2 passUv;
out vec4 fragColor;

void main() {
    vec4 sum = texture(uSource, passUv + vec2(-uHalfPixel.x * 2.0, 0.0) * uOffset);
    sum += texture(uSource, passUv + vec2(-uHalfPixel.x, uHalfPixel.y) * uOffset) * 2.0;
    sum += texture(uSource, passUv + vec2(0.0, uHalfPixel.y * 2.0) * uOffset);
    sum += texture(uSource, passUv + vec2(uHalfPixel.x, uHalfPixel.y) * uOffset) * 2.0;
    sum += texture(uSource, passUv + vec2(uHalfPixel.x * 2.0, 0.0) * uOffset);
    sum += texture(uSource, passUv + vec2(uHalfPixel.x, -uHalfPixel.y) * uOffset) * 2.0;
    sum += texture(uSource, passUv + vec2(0.0, -uHalfPixel.y * 2.0) * uOffset);
    sum += texture(uSource, passUv + vec2(-uHalfPixel.x, -uHalfPixel.y) * uOffset) * 2.0;
    fragColor = sum / 12.0;
}
