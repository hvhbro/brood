#version 140

uniform sampler2D textureSampler;
uniform sampler2D lightmapSampler;

in vec2 passTexCoords;
in vec3 passLightmapCoords;

out vec4 finalColor;

void main() {
    vec4 textureColor = texture(textureSampler, passTexCoords);

    if (passLightmapCoords.z == 0.0) {
        finalColor = textureColor;
        return;
    }

    finalColor = textureColor * texture(lightmapSampler, passLightmapCoords.xy);
}
