#version 140

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;

uniform samplerBuffer instanceData;
uniform int instanceRecordBase;
uniform int instanceRecordTexels;

in vec3 position;
in vec4 color;
in vec2 texCoords;
in vec3 normal;
in int boneIndex;

out vec4 fragColor;
centroid out vec2 fragTexCoords;
out vec3 fragNormal_world;
out vec3 fragLightmapCoords;

void main() {
    int record = instanceRecordBase + gl_InstanceID * instanceRecordTexels;
    int matrixTexel = record + boneIndex * 4;

    mat4 boneMatrix = mat4(
        texelFetch(instanceData, matrixTexel),
        texelFetch(instanceData, matrixTexel + 1),
        texelFetch(instanceData, matrixTexel + 2),
        texelFetch(instanceData, matrixTexel + 3)
    );

    gl_Position = projectionMatrix * viewMatrix * boneMatrix * vec4(position, 1.0);
    fragNormal_world = normalize((boneMatrix * vec4(normal, 0.0)).xyz);

    fragColor = color;
    fragTexCoords = texCoords;
    fragLightmapCoords = texelFetch(instanceData, record + instanceRecordTexels - 1).xyz;
}
