#version 140

uniform sampler2D textureSampler;
uniform sampler2D lightmapSampler;
uniform mat4 viewMatrix;

uniform vec3 dollLightDirection;
uniform vec3 dollLightColor;
uniform vec3 dollAmbientColor;

in vec4 fragColor;
centroid in vec2 fragTexCoords;
in vec3 fragNormal_world;
in vec3 fragLightmapCoords;

out vec4 finalColor;

vec3 getDollLight() {
    vec3 normal = normalize(mat3(viewMatrix) * fragNormal_world);
    return dollAmbientColor + dollLightColor * max(dot(normal, dollLightDirection), 0.0);
}

float getAO() {
    float worldSpaceAO = smoothstep(-0.4, 0.6, fragNormal_world.y);

    float minAmbient = 0.5;
    float maxAmbient = 0.7;

    float finalAmbient = mix(minAmbient, maxAmbient, worldSpaceAO);
    float diffuseFactor = max(dot(fragNormal_world, vec3(-0.5, 1.0, -0.5)), 0.0);
    float diffuseStrength = 1.0 - maxAmbient;

    return finalAmbient + diffuseStrength * diffuseFactor;
}

void main() {
    vec4 textureColor = texture(textureSampler, fragTexCoords);

    if (textureColor.a < 0.1) discard;

    vec3 colorWithoutShadow = textureColor.rgb * fragColor.rgb;

    if (fragLightmapCoords.z < -0.5) {
        finalColor = vec4(colorWithoutShadow * getDollLight(), textureColor.a);
        return;
    }

    float ao = getAO();

    if (fragLightmapCoords.z == 0.0) {
        finalColor = vec4(colorWithoutShadow * ao, textureColor.a);
        return;
    }

    vec4 lightmapColor = texture(lightmapSampler, fragLightmapCoords.xy);

    finalColor = vec4(colorWithoutShadow * lightmapColor.rgb * ao, textureColor.a);
}