#version 130

uniform sampler2D uBlur;
uniform float uStrength;

in vec2 passUv;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(uBlur, passUv).rgb, uStrength);
}
