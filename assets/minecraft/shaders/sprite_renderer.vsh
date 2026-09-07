#version 140

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;

in vec3 position;
in vec4 color;
in vec2 texCoords;

in mat4 instanceModelMatrix;
in vec3 instanceLightmapCoord;
in vec4 instanceUVs;
in vec4 instanceColor;

varying vec4 passColor;
varying vec2 passTexCoords;
varying vec3 passLightmapCoords;

void main() {
    vec3 particleCenter = instanceModelMatrix[3].xyz;

    vec3 cameraRight = vec3(viewMatrix[0][0], viewMatrix[1][0], viewMatrix[2][0]);
    vec3 cameraUp = vec3(viewMatrix[0][1], viewMatrix[1][1], viewMatrix[2][1]);

    float particleScale = length(instanceModelMatrix[0]);

    vec3 finalVertexPos = particleCenter
    + cameraRight * position.x * particleScale
    + cameraUp * position.y * particleScale;

    gl_Position = projectionMatrix * viewMatrix * vec4(finalVertexPos, 1.0);

    float final_u = mix(instanceUVs.x, instanceUVs.z, texCoords.x);
    float final_v = mix(instanceUVs.y, instanceUVs.w, texCoords.y);
    passTexCoords = vec2(final_u, final_v);

    passLightmapCoords = instanceLightmapCoord;
    passColor = instanceColor;
}