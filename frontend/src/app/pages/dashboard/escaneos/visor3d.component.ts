import {
  Component, ElementRef, EventEmitter, Input, Output,
  AfterViewInit, OnDestroy, ViewChild, signal,
} from '@angular/core';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js';
import { OBJLoader } from 'three/examples/jsm/loaders/OBJLoader.js';

/**
 * Visor 3D embebido (three.js) para previsualizar escaneos STL/OBJ dentro del
 * sistema, sin abrir un programa externo. Órbita, zoom y paneo con el mouse.
 */
@Component({
  selector: 'app-visor3d',
  standalone: true,
  template: `
    <div class="v3d-overlay" (click)="onCerrar()">
      <div class="v3d-modal" (click)="$event.stopPropagation()">
        <div class="v3d-head">
          <span class="v3d-title">{{ fileName }}</span>
          <button class="v3d-close" (click)="onCerrar()" aria-label="Cerrar">✕</button>
        </div>

        <div class="v3d-canvas" #host>
          @if (cargando()) {
            <div class="v3d-state"><span class="v3d-spinner"></span> Cargando modelo 3D…</div>
          }
          @if (error()) {
            <div class="v3d-state v3d-err">{{ error() }}</div>
          }
        </div>

        <div class="v3d-hint">Arrastrá para rotar · rueda para zoom · clic derecho para mover</div>
      </div>
    </div>
  `,
  styleUrls: ['./visor3d.component.css'],
})
export class Visor3dComponent implements AfterViewInit, OnDestroy {
  @Input({ required: true }) url!: string;
  @Input() fileName = '';
  @Output() cerrar = new EventEmitter<void>();
  @ViewChild('host', { static: false }) host!: ElementRef<HTMLDivElement>;

  readonly cargando = signal(true);
  readonly error = signal('');

  private renderer?: THREE.WebGLRenderer;
  private scene?: THREE.Scene;
  private camera?: THREE.PerspectiveCamera;
  private controls?: OrbitControls;
  private frameId = 0;
  private ro?: ResizeObserver;

  ngAfterViewInit(): void {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    const el = this.host?.nativeElement;
    if (!el) return;
    const w = el.clientWidth || 600;
    const h = el.clientHeight || 400;

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(45, w / h, 0.1, 10000);
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setSize(w, h);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    el.appendChild(renderer.domElement);

    scene.add(new THREE.AmbientLight(0xffffff, 1.1));
    const key = new THREE.DirectionalLight(0xffffff, 1.4); key.position.set(1, 1, 1.5); scene.add(key);
    const fill = new THREE.DirectionalLight(0xffffff, 0.7); fill.position.set(-1, -0.5, -1); scene.add(fill);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;

    this.scene = scene;
    this.camera = camera;
    this.renderer = renderer;
    this.controls = controls;

    this.loadModel();

    const animate = () => {
      this.frameId = requestAnimationFrame(animate);
      controls.update();
      renderer.render(scene, camera);
    };
    animate();

    this.ro = new ResizeObserver(() => this.onResize());
    this.ro.observe(el);
  }

  private loadModel(): void {
    const ext = (this.fileName.split('.').pop() || '').toLowerCase();
    const onError = () => { this.error.set('No se pudo cargar el modelo 3D.'); this.cargando.set(false); };

    if (ext === 'stl') {
      new STLLoader().load(this.url, (geo) => {
        geo.computeVertexNormals();
        const mat = new THREE.MeshStandardMaterial({ color: 0x2dd4a7, metalness: 0.15, roughness: 0.55 });
        this.addAndFit(new THREE.Mesh(geo, mat));
      }, undefined, onError);
    } else if (ext === 'obj') {
      new OBJLoader().load(this.url, (obj) => {
        obj.traverse((c) => {
          if ((c as THREE.Mesh).isMesh) {
            (c as THREE.Mesh).material = new THREE.MeshStandardMaterial({ color: 0x2dd4a7, metalness: 0.15, roughness: 0.55 });
          }
        });
        this.addAndFit(obj);
      }, undefined, onError);
    } else {
      this.error.set('El visor solo soporta STL y OBJ. Descargá el archivo para abrirlo en tu programa.');
      this.cargando.set(false);
    }
  }

  /** Centra el objeto en el origen y ubica la cámara para que entre completo. */
  private addAndFit(object: THREE.Object3D): void {
    const scene = this.scene!, camera = this.camera!, controls = this.controls!;
    const box = new THREE.Box3().setFromObject(object);
    const size = box.getSize(new THREE.Vector3());
    const center = box.getCenter(new THREE.Vector3());
    object.position.sub(center);
    scene.add(object);

    const maxDim = Math.max(size.x, size.y, size.z) || 1;
    const fov = camera.fov * (Math.PI / 180);
    const dist = (maxDim / 2 / Math.tan(fov / 2)) * 1.8;
    camera.position.set(dist * 0.4, dist * 0.3, dist);
    camera.near = dist / 100;
    camera.far = dist * 100;
    camera.updateProjectionMatrix();
    controls.target.set(0, 0, 0);
    controls.update();

    this.cargando.set(false);
  }

  private onResize(): void {
    const el = this.host?.nativeElement;
    if (!el || !this.renderer || !this.camera) return;
    const w = el.clientWidth, h = el.clientHeight;
    if (!w || !h) return;
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(w, h);
  }

  onCerrar(): void { this.cerrar.emit(); }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frameId);
    this.ro?.disconnect();
    this.controls?.dispose();
    this.scene?.traverse((o) => {
      const m = o as THREE.Mesh;
      if (m.geometry) m.geometry.dispose();
      if (m.material) (Array.isArray(m.material) ? m.material : [m.material]).forEach((x) => x.dispose());
    });
    this.renderer?.dispose();
    this.renderer?.domElement.remove();
  }
}
