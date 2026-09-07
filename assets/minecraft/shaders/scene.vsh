#version 140

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;

in mat4 instanceModelMatrix;
in vec3 instanceLightmapCoord;

in vec3 position;
in vec4 color;
in vec2 texCoords;
in vec3 normal;

out vec4 fragColor;
centroid out vec2 fragTexCoords;
out vec3 fragNormal_world;
out vec3 fragLightmapCoords;

void main() {
    gl_Position = projectionMatrix * viewMatrix * instanceModelMatrix * vec4(position, 1.0);
    fragNormal_world = normalize((instanceModelMatrix * vec4(normal, 0.0)).xyz);

    fragColor = color;
    fragTexCoords = texCoords;
    fragLightmapCoords = instanceLightmapCoord;
}