import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement>;

const commonProps = {
  fill: "none",
  stroke: "currentColor",
  strokeLinecap: "round",
  strokeLinejoin: "round",
  strokeWidth: 1.8,
  viewBox: "0 0 24 24",
} as const;

export function LogoMark(props: IconProps) {
  return (
    <svg aria-hidden="true" {...commonProps} {...props}>
      <path d="M4 17.5 9 12l3 3 8-9" />
      <path d="M15 6h5v5" />
      <path d="M4 21h16" />
    </svg>
  );
}

export function SearchIcon(props: IconProps) {
  return (
    <svg aria-hidden="true" {...commonProps} {...props}>
      <circle cx="11" cy="11" r="6.5" />
      <path d="m16 16 4 4" />
    </svg>
  );
}

export function ArrowRightIcon(props: IconProps) {
  return (
    <svg aria-hidden="true" {...commonProps} {...props}>
      <path d="M5 12h14" />
      <path d="m14 7 5 5-5 5" />
    </svg>
  );
}

export function ShieldIcon(props: IconProps) {
  return (
    <svg aria-hidden="true" {...commonProps} {...props}>
      <path d="M12 3 5 6v5c0 4.5 2.7 8.1 7 10 4.3-1.9 7-5.5 7-10V6l-7-3Z" />
      <path d="m9 12 2 2 4-4" />
    </svg>
  );
}

export function DatabaseIcon(props: IconProps) {
  return (
    <svg aria-hidden="true" {...commonProps} {...props}>
      <ellipse cx="12" cy="5.5" rx="7" ry="3" />
      <path d="M5 5.5v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" />
      <path d="M5 11.5v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" />
    </svg>
  );
}

export function ActivityIcon(props: IconProps) {
  return (
    <svg aria-hidden="true" {...commonProps} {...props}>
      <path d="M3 12h4l2-6 4 12 2-6h6" />
    </svg>
  );
}

export function ClockIcon(props: IconProps) {
  return (
    <svg aria-hidden="true" {...commonProps} {...props}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7v5l3 2" />
    </svg>
  );
}
