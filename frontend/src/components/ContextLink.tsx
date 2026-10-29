import { Link } from 'react-router'
import type { LinkProps } from 'react-router'
import { useContextNavigation } from '../hooks/useContextNavigation'

export function ContextLink({ to, onClick, ...props }: LinkProps & { to: string }) {
  const navigate = useContextNavigation()
  return (
    <Link
      {...props}
      to={to}
      onClick={(event) => {
        onClick?.(event)
        if (
          event.defaultPrevented ||
          event.button !== 0 ||
          event.metaKey ||
          event.ctrlKey ||
          event.shiftKey ||
          event.altKey ||
          (props.target && props.target !== '_self')
        )
          return
        event.preventDefault()
        void navigate(to)
      }}
    />
  )
}
