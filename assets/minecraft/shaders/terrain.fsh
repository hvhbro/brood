#version 140

#ifndef ARRAY_COUNT
#define ARRAY_COUNT 4
#endif
#ifndef LAYERS_PER_ARRAY
#define LAYERS_PER_ARRAY 2048
#endif

uniform sampler2DArray textureSampler0;
#if ARRAY_COUNT > 1
uniform sampler2DArray textureSampler1;
#endif
#if ARRAY_COUNT > 2
uniform sampler2DArray textureSampler2;
#endif
#if ARRAY_COUNT > 3
uniform sampler2DArray textureSampler3;
#endif
uniform sampler2D lightmapSampler;

uniform int debugLayerView;


uniform int fogMode;
uniform vec4 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float fogDensity;

in vec4 fragColor;
centroid in vec2 fragUv;
in vec2 fragLightCoords;
in float fragFogDistance;

flat in float fragLayer;

out vec4 finalColor;

void main() {
    float globalLayer = fragLayer;

    vec4 textureColor;

#if ARRAY_COUNT == 1
    textureColor = texture(textureSampler0, vec3(fragUv, globalLayer));
#else

    int layerIndex = int(globalLayer + 0.5);
    int arrayIndex = layerIndex / LAYERS_PER_ARRAY;
    vec3 uvw = vec3(fragUv, float(layerIndex - arrayIndex * LAYERS_PER_ARRAY));

    vec2 dx = dFdx(fragUv);
    vec2 dy = dFdy(fragUv);

    #if ARRAY_COUNT > 3
    if (arrayIndex == 3) textureColor = textureGrad(textureSampler3, uvw, dx, dy); else
    #endif
    #if ARRAY_COUNT > 2
    if (arrayIndex == 2) textureColor = textureGrad(textureSampler2, uvw, dx, dy); else
    #endif
    if (arrayIndex == 1) textureColor = textureGrad(textureSampler1, uvw, dx, dy);
    else textureColor = textureGrad(textureSampler0, uvw, dx, dy);

#endif

    if (debugLayerView != 0) {
        finalColor = vec4(
            fract(globalLayer / 16.0) * (16.0 / 15.0),
            fract(floor(globalLayer / 16.0) / 16.0) * (16.0 / 15.0),
            fract(floor(globalLayer / 256.0) / 16.0) * (16.0 / 15.0),
            textureColor.a * fragColor.a
        );
        return;
    }

    vec4 lightmapColor = texture(lightmapSampler, fragLightCoords);
    vec3 litColor = textureColor.rgb * fragColor.rgb * lightmapColor.rgb;

    if (fogMode == 0) {
        finalColor = vec4(litColor, textureColor.a * fragColor.a);
        return;
    }

    float fogFactor;
    if (fogMode == 1) {
        fogFactor = clamp((fogEnd - fragFogDistance) / (fogEnd - fogStart), 0.0, 1.0);
    } else if (fogMode == 2) {
        fogFactor = clamp(exp(-fogDensity * fragFogDistance), 0.0, 1.0);
    } else {
        float d = fogDensity * fragFogDistance;
        fogFactor = clamp(exp(-d * d), 0.0, 1.0);
    }

    finalColor = vec4(mix(fogColor.rgb, litColor, fogFactor), textureColor.a * fragColor.a);
}
