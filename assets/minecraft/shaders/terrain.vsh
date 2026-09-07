#version 140

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform vec3 regionOffset;

in vec4 posLayer;
in vec4 color;
in vec4 lightUv;

out vec4 fragColor;
centroid out vec2 fragUv;

out vec2 fragLightCoords;
out float fragFogDistance;

flat out float fragLayer;

void main() {
    vec3 localPosition = posLayer.xyz * vec3(POSITION_SCALE_XZ, POSITION_SCALE_Y, POSITION_SCALE_XZ) + POSITION_BIAS;
    vec4 eyePosition = viewMatrix * vec4(localPosition + regionOffset, 1.0);

    gl_Position = projectionMatrix * eyePosition;

    fragColor = color;
    fragUv = lightUv.zw;
    fragLayer = posLayer.w * 65535.0;
    fragLightCoords = (lightUv.xy * 255.0 + 8.0) / 256.0;
    fragFogDistance = length(eyePosition.xyz);
}
