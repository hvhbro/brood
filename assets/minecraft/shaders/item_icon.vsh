#version 140

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;

in vec3 position;
in vec2 texCoords;

in mat4 instanceModelMatrix;
in vec3 instanceLightmapCoord;
in vec4 instanceUVs;

out vec2 passTexCoords;
out vec3 passLightmapCoords;

void main() {
    gl_Position = projectionMatrix * viewMatrix * instanceModelMatrix * vec4(position, 1.0);
    passTexCoords = vec2(
        mix(instanceUVs.x, instanceUVs.z, texCoords.x),
        mix(instanceUVs.y, instanceUVs.w, texCoords.y)
    );
    passLightmapCoords = instanceLightmapCoord;
}
