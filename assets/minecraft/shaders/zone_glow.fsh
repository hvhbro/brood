#version 130

uniform sampler2D uMask;
uniform vec3 uColor;
uniform float uStrength;

in vec2 passUv;
out vec4 fragColor;

void main() {
    float glow = texture(uMask, passUv).a;

    fragColor = vec4(uColor, glow * uStrength);
}
