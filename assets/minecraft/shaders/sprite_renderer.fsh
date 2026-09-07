#version 140

uniform sampler2D textureSampler;
uniform sampler2D lightmapSampler;

in vec4 passColor;
in vec2 passTexCoords;
in vec3 passLightmapCoords;

out vec4 finalColor;

void main() {
    vec4 textureColor = texture(textureSampler, passTexCoords);

    if (textureColor.a * passColor.a < 0.01) {
        discard;
    }

    vec4 lightmapColor = texture(lightmapSampler, passLightmapCoords.xy);

    finalColor = textureColor * passColor * lightmapColor;
}