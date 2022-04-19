import React from 'react'
import useDropdownMenu from './menu-items'
import styles from '../styles.module.css'

export interface MenubuttonProps {
    title: string
    onClick?: () => void
}

export interface ButtonsProps {
    buttons: MenubuttonProps[]
}

const MenuButton = (props: ButtonsProps) => {
    const { setIsOpen, isOpen } = useDropdownMenu(3)

    return (
        <div className={styles.menuWrapper}>
            <button className="ui button dropdown icon change-org-user" onClick={() => setIsOpen(!isOpen)} type='button'>
                Settings
            </button>

            <div className={`${styles.menu} ${isOpen ? styles.menuVisible : styles.menuClosed}`}>
                {
                    props.buttons.map((button, index) => (
                      <a className={styles.menuItem} onClick={() => button.onClick?.()} key={`menu-item-${index}`}>
                        {button.title}
                      </a>
                    ))
                }
            </div>
        </div>
    )
}

export default MenuButton
