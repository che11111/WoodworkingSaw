import { createRequire } from 'node:module';
import { readFile, mkdir, writeFile, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const requireFromApp = createRequire(
  '/Applications/ChatGPT.app/Contents/Resources/cua_node/lib/node_modules/playwright/package.json',
);
const { chromium } = requireFromApp('playwright');

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '..');
const outputDir = join(projectRoot, 'img');
const tempFramesDir = '/tmp/woodworking-saw-frames';
const modelPath = join(projectRoot, '模型', 'simple_woodworking_table.bbmodel');
const animationPath = join(
  projectRoot,
  'src',
  'main',
  'resources',
  'craftengine',
  'resourcepack',
  'assets',
  'woodworkingsaw',
  'textures',
  'block',
  'stonecutter_saw.png.mcmeta',
);
const threeRoot = '/tmp/wg-three/node_modules/three';

const model = JSON.parse(await readFile(modelPath, 'utf8'));
const textureAnimation = JSON.parse(await readFile(animationPath, 'utf8')).animation;
const sawGroupId = 'f6d79725-7ff7-0a69-b6e9-b17d09b2015d';
const sawIds = new Set(
  model.outliner
    .find((item) => typeof item === 'object' && item.uuid === sawGroupId)
    ?.children ?? [],
);

const decoder = (source) => source.slice(source.indexOf(',') + 1);
const textures = model.textures.map((texture) => ({
  ...texture,
  source: decoder(texture.source),
}));

const html = `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    html, body {
      width: 100%;
      height: 100%;
      margin: 0;
      overflow: hidden;
      background: ${'linear-gradient(145deg, #332d28 0%, #1f1c19 58%, #151311 100%)'};
    }
    canvas { display: block; width: 100%; height: 100%; }
  </style>
  <script type="importmap">
    {"imports":{"three":"/three/three.module.js"}}
  </script>
</head>
<body>
<script type="module">
import * as THREE from 'three';

const model = ${JSON.stringify(model)};
const textures = ${JSON.stringify(textures)};
const sawIds = new Set(${JSON.stringify([...sawIds])});
const rad = Math.PI / 180;

let renderer, scene, camera, sawPivot, ground;
const sawTextureMaterials = [];

const faceNormals = {
  north: [0, 0, -1],
  south: [0, 0, 1],
  east: [1, 0, 0],
  west: [-1, 0, 0],
  up: [0, 1, 0],
  down: [0, -1, 0],
};

function faceData(name, from, to) {
  const [x1, y1, z1] = from;
  const [x2, y2, z2] = to;
  const north = [[x1,y2,z1],[x2,y2,z1],[x2,y1,z1],[x1,y1,z1]];
  const south = [[x2,y2,z2],[x1,y2,z2],[x1,y1,z2],[x2,y1,z2]];
  const east  = [[x2,y2,z1],[x2,y2,z2],[x2,y1,z2],[x2,y1,z1]];
  const west  = [[x1,y2,z2],[x1,y2,z1],[x1,y1,z1],[x1,y1,z2]];
  const up    = [[x1,y2,z2],[x2,y2,z2],[x2,y2,z1],[x1,y2,z1]];
  const down  = [[x1,y1,z1],[x2,y1,z1],[x2,y1,z2],[x1,y1,z2]];
  return { north, south, east, west, up, down }[name];
}

function makeFace(face, material, normal) {
  const geometry = new THREE.BufferGeometry();
  const positions = new Float32Array(face.flat());
  const uvs = new Float32Array([
    0, 1,
    1, 1,
    1, 0,
    0, 0,
  ]);
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  geometry.setAttribute('uv', new THREE.BufferAttribute(uvs, 2));
  geometry.setAttribute(
    'normal',
    new THREE.BufferAttribute(new Float32Array(face.flatMap(() => normal)), 3),
  );
  geometry.setIndex([0, 1, 2, 0, 2, 3]);
  const mesh = new THREE.Mesh(geometry, material);
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  return mesh;
}

function makeCube(element, materials) {
  const cubePivot = new THREE.Group();
  const origin = element.origin ?? [0, 0, 0];
  cubePivot.position.set(...origin);
  const rotation = element.rotation ?? [0, 0, 0];
  cubePivot.rotation.set(rotation[0] * rad, rotation[1] * rad, rotation[2] * rad, 'XYZ');

  for (const [faceName, faceInfo] of Object.entries(element.faces ?? {})) {
    if (faceInfo.texture === null || faceInfo.texture === undefined) continue;
    const sourceTexture = textures[faceInfo.texture];
    if (!sourceTexture) continue;
    const material = materials[faceInfo.texture].clone();
    const [u1, v1, u2, v2] = faceInfo.uv;
    const internalWidth = model.resolution?.width || 64;
    const uvWidth = sourceTexture.uv_width || internalWidth;
    const uvHeight = sourceTexture.uv_height || internalHeight;
    const uvScale = internalWidth / uvWidth;
    const clonedMap = material.map.clone();
    const imageHeight = clonedMap.image.height;
    clonedMap.repeat.set(
      (u2 - u1) / (uvWidth * uvScale),
      (v2 - v1) / (uvScale * imageHeight),
    );
    const baseOffsetY = 1 - (v2 / uvScale) / imageHeight;
    clonedMap.offset.set(u1 / (uvWidth * uvScale), baseOffsetY);
    clonedMap.needsUpdate = true;
    material.map = clonedMap;
    material.alphaTest = sourceTexture.name === 'stonecutter_saw.png' ? 0.08 : 0;
    material.transparent = sourceTexture.name === 'stonecutter_saw.png';
    material.depthWrite = true;
    material.side = THREE.DoubleSide;
    if (sourceTexture.name === 'stonecutter_saw.png') {
      sawTextureMaterials.push({
        map: clonedMap,
        baseOffsetY,
        frameStepY: uvHeight / imageHeight,
      });
    }
    const face = faceData(faceName, element.from, element.to);
    const normal = faceNormals[faceName];
    const localFace = face.map((point) => point.map((value, index) => value - origin[index]));
    const mesh = makeFace(localFace, material, normal);
    cubePivot.add(mesh);
  }
  return cubePivot;
}

async function makeTexture(texture) {
  const image = new Image();
  image.src = 'data:image/png;base64,' + texture.source;
  await image.decode();
  const map = new THREE.Texture(image);
  map.magFilter = THREE.NearestFilter;
  map.minFilter = THREE.NearestFilter;
  map.colorSpace = THREE.SRGBColorSpace;
  map.wrapS = THREE.ClampToEdgeWrapping;
  map.wrapT = THREE.ClampToEdgeWrapping;
  map.needsUpdate = true;
  return map;
}

async function init({ width, height, transparent }) {
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(1);
  renderer.setSize(width, height, false);
  renderer.setClearColor(0x000000, transparent ? 0 : 1);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.12;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  document.documentElement.style.background = transparent ? 'transparent' : '';
  document.body.style.background = transparent ? 'transparent' : '';
  document.body.replaceChildren(renderer.domElement);

  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(30, width / height, 0.1, 200);
  camera.position.set(39, 24, transparent && width === height ? 38 : 30);
  camera.lookAt(8, 6.4, 8);

  const hemi = new THREE.HemisphereLight(0xfff4de, 0x685646, 2.1);
  scene.add(hemi);
  const key = new THREE.DirectionalLight(0xffe7bd, 3.1);
  key.position.set(-12, 32, 25);
  key.castShadow = true;
  key.shadow.mapSize.set(2048, 2048);
  key.shadow.camera.left = -25;
  key.shadow.camera.right = 25;
  key.shadow.camera.top = 25;
  key.shadow.camera.bottom = -25;
  key.shadow.camera.near = 1;
  key.shadow.camera.far = 80;
  key.shadow.bias = -0.00025;
  key.shadow.normalBias = 0.02;
  scene.add(key);
  const rim = new THREE.DirectionalLight(0x9cc8ff, 1.2);
  rim.position.set(22, 14, -22);
  scene.add(rim);

  const maps = await Promise.all(textures.map(makeTexture));
  const materials = maps.map((map) => new THREE.MeshStandardMaterial({
    map,
    roughness: 0.82,
    metalness: 0.0,
  }));

  const root = new THREE.Group();
  scene.add(root);
  sawPivot = new THREE.Group();
  sawPivot.position.set(8, 7.5, 8);
  root.add(sawPivot);

  for (const element of model.elements) {
    const cube = makeCube(element, materials);
    if (sawIds.has(element.uuid)) {
      cube.position.sub(sawPivot.position);
      sawPivot.add(cube);
    } else {
      root.add(cube);
    }
  }
  sawPivot.rotation.x = 0;

  if (!transparent) {
    ground = new THREE.Mesh(
      new THREE.PlaneGeometry(60, 60),
      new THREE.ShadowMaterial({ color: 0x000000, opacity: 0.22 }),
    );
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -0.06;
    ground.receiveShadow = true;
    scene.add(ground);
  }

  renderer.render(scene, camera);
  window.renderer = renderer;
  window.setSawTextureFrame = (frame) => {
    for (const entry of sawTextureMaterials) {
      entry.map.offset.y = entry.baseOffsetY - frame * entry.frameStepY;
      entry.map.needsUpdate = true;
    }
  };
  window.renderScene = () => renderer.render(scene, camera);
  window.__ready = true;
}

window.initScene = init;
</script>
</body>
</html>`;

const server = createServer(async (req, res) => {
  try {
    if (req.url === '/') {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end(html);
      return;
    }
    if (req.url?.startsWith('/three/') && !req.url.includes('..')) {
      const filename = req.url.slice('/three/'.length);
      res.writeHead(200, { 'content-type': 'text/javascript; charset=utf-8' });
      res.end(await readFile(join(threeRoot, 'build', filename)));
      return;
    }
    res.writeHead(404);
    res.end('Not found');
  } catch (error) {
    res.writeHead(500);
    res.end(String(error));
  }
});

await new Promise((resolveServer) => server.listen(0, '127.0.0.1', resolveServer));
const port = server.address().port;
await mkdir(outputDir, { recursive: true });
await rm(tempFramesDir, { recursive: true, force: true });
await mkdir(tempFramesDir, { recursive: true });

const browser = await chromium.launch({
  headless: true,
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  args: ['--use-gl=angle', '--use-angle=metal', '--enable-webgl'],
});

try {
  const staticPage = await browser.newPage({
    viewport: { width: 1200, height: 900 },
    deviceScaleFactor: 1,
  });
  staticPage.on('console', (message) => {
    if (message.type() === 'error') console.error(message.text());
  });
  staticPage.on('pageerror', (error) => console.error(error));
  await staticPage.goto(`http://127.0.0.1:${port}/`);
  await staticPage.evaluate(() => window.initScene({
    width: 1200,
    height: 900,
    transparent: false,
  }));
  await staticPage.waitForFunction(() => window.__ready === true);
  await staticPage.screenshot({
    path: join(outputDir, 'WoodworkingSaw_render.png'),
    omitBackground: false,
  });

  const page = await browser.newPage({
    viewport: { width: 720, height: 540 },
    deviceScaleFactor: 1,
  });
  page.on('pageerror', (error) => console.error(error));
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.evaluate(() => window.initScene({
    width: 720,
    height: 540,
    transparent: false,
  }));
  await page.waitForFunction(() => window.__ready === true);

  const animationFrames = textureAnimation.frames ?? [0, 1, 2];
  for (let index = 0; index < animationFrames.length; index += 1) {
    await page.evaluate((textureFrame) => {
      window.setSawTextureFrame(textureFrame);
      window.renderScene();
    }, animationFrames[index]);
    await page.screenshot({
      path: join(tempFramesDir, `frame_${String(index).padStart(3, '0')}.png`),
      omitBackground: false,
    });
  }
  await writeFile(
    join(outputDir, 'WoodworkingSaw_render_meta.json'),
    JSON.stringify({
      source: 'Blockbench 5.2.2',
      model: '模型/simple_woodworking_table.bbmodel',
      animation: 'stonecutter_saw.png material animation',
      game_animation: textureAnimation,
      frames: animationFrames.length,
      duration_seconds: animationFrames.length * textureAnimation.frametime / 20,
      frame_size: [720, 540],
    }, null, 2) + '\n',
  );

  const transparentPage = await browser.newPage({
    viewport: { width: 720, height: 720 },
    deviceScaleFactor: 1,
  });
  transparentPage.on('pageerror', (error) => console.error(error));
  await transparentPage.goto(`http://127.0.0.1:${port}/`);
  await transparentPage.evaluate(() => window.initScene({
    width: 720,
    height: 720,
    transparent: true,
  }));
  await transparentPage.waitForFunction(() => window.__ready === true);
  await transparentPage.screenshot({
    path: join(outputDir, 'WoodworkingSaw_transparent_1x1.png'),
    omitBackground: true,
  });
  await rm('/tmp/woodworking-saw-transparent-frames', { recursive: true, force: true });
  await mkdir('/tmp/woodworking-saw-transparent-frames', { recursive: true });
  for (let index = 0; index < animationFrames.length; index += 1) {
    await transparentPage.evaluate((textureFrame) => {
      window.setSawTextureFrame(textureFrame);
      window.renderScene();
    }, animationFrames[index]);
    await transparentPage.screenshot({
      path: join(
        '/tmp/woodworking-saw-transparent-frames',
        `frame_${String(index).padStart(3, '0')}.png`,
      ),
      omitBackground: true,
    });
  }
} finally {
  await browser.close();
  server.close();
}

console.log(`Static render: ${join(outputDir, 'WoodworkingSaw_render.png')}`);
console.log(`Animation frames: ${tempFramesDir}`);
